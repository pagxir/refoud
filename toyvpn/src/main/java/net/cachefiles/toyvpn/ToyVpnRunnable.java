/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.cachefiles.toyvpn;

import static java.nio.charset.StandardCharsets.US_ASCII;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import android.system.StructPollfd;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileDescriptor;
import java.lang.reflect.Field;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import android.net.LinkProperties;
import android.net.Network;
import java.net.PortUnreachableException;
import java.net.Inet4Address;
import android.net.NetworkCapabilities;
import java.nio.channels.FileChannel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.DatagramChannel;
import java.net.StandardSocketOptions;
import java.util.Set;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ToyVpnRunnable implements Runnable {
    /**
     * Callback interface to let the {@link ToyVpnService} know about new connections
     * and update the foreground notification with connection status.
     */
    public interface OnConnectListener {
	public enum Stage {
	    taskLaunch, connecting, establish, disconnected, taskTerminate;
	}
	void onConnectStage(Stage stage);
    }

    /** Maximum packet size is constrained by the MTU, which is given as a signed short. */
    // private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;
    private static final int MAX_PACKET_SIZE = 8192;

    /** Time to wait in between losing the connection and retrying. */
    private static final long RECONNECT_WAIT_MS = TimeUnit.SECONDS.toMillis(3);

    /** Time between keepalives if there is no traffic at the moment.
     *
     * TODO: don't do this; it's much better to let the connection die and then reconnect when
     *       necessary instead of keeping the network hardware up for hours on end in between.
     **/
    private static final long KEEPALIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);

    /** Time to wait without receiving any response before assuming the server is gone. */
    private static final long RECEIVE_TIMEOUT_MS = KEEPALIVE_INTERVAL_MS * 3;

    /**
     * Time between polling the VPN interface for new traffic, since it's non-blocking.
     *
     * TODO: really don't do this; a blocking read on another thread is much cleaner.
     */
    private static final long IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100);

    /**
     * Number of periods of length {@IDLE_INTERVAL_MS} to wait before declaring the handshake a
     * complete and abject failure.
     *
     * TODO: use a higher-level protocol; hand-rolling is a fun but pointless exercise.
     */
    private static final int MAX_HANDSHAKE_ATTEMPTS = 50;

    private final VpnService mService;
    private final int mConnectionId;

    private final String mServerName;
    private final int mServerPort;
    private final byte[] mSharedSecret;

    private PendingIntent mConfigureIntent;
    private OnConnectListener mOnConnectListener;
    private ConnectivityManager mManager = null;
    private static final String LOG_TAG = "ToyVpnRunnable_1";

    // Proxy settings
    private String mProxyHostName;
    private int mProxyHostPort;

    // DNS server
    private String mDnsServer;

    // Allowed/Disallowed packages for VPN usage
    private final boolean mAllow;
    private final Set<String> mPackages;

    public ToyVpnRunnable(final VpnService service, final int connectionId,
	    final String serverName, final int serverPort, final byte[] sharedSecret,
	    final String proxyHostName, final int proxyHostPort, boolean allow,
	    final Set<String> packages, final String dnsServer) {
	mService = service;
	mConnectionId = connectionId;

	mServerName = serverName;
	mServerPort= serverPort;
	mSharedSecret = sharedSecret;
	mManager = (ConnectivityManager) mService.getSystemService(Context.CONNECTIVITY_SERVICE);

	if (!TextUtils.isEmpty(proxyHostName)) {
	    mProxyHostName = proxyHostName;
	}
	if (proxyHostPort > 0) {
	    // The port value is always an integer due to the configured inputType.
	    mProxyHostPort = proxyHostPort;
	}
	mDnsServer = dnsServer;
	mAllow = allow;
	mPackages = packages;
    }

    /**
     * Optionally, set an intent to configure the VPN. This is {@code null} by default.
     */
    public void setConfigureIntent(PendingIntent intent) {
	mConfigureIntent = intent;
    }

    public void setOnConnectListener(OnConnectListener listener) {
	mOnConnectListener = listener;
    }

    boolean networkChange = true;
    Map<String, Network> networkMap = new HashMap<>();
    final ConnectivityManager.NetworkCallback mCallback = new ConnectivityManager.NetworkCallback() {

	@Override
	public void onAvailable(Network network) {
	    super.onAvailable(network);
	    String netId = network.toString();
	    Log.d(LOG_TAG, "NetworkStateCallback.onAvailable " + netId);

	    networkMap.put(netId, network);
	    networkChange = true;
	    // oldThread.interrupt();
	}

	@Override
	public void onLost(Network network) {
	    super.onLost(network);
	    String netId = network.toString();
	    Log.d(LOG_TAG, "NetworkStateCallback.onLost " + netId);

	    networkMap.remove(netId);
	}

	@Override
	public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
	    super.onCapabilitiesChanged(network, capabilities);
	    String netId = network.toString();
	    Log.d(LOG_TAG, "NetworkStateCallback.onCapabilitiesChanged " + netId);
	    networkChange = true;
	    // oldThread.interrupt();
	}
    };

    private Network getUnderlyingNetwork(ConnectivityManager manager) {
	int priority = -1;
	Network underlyingNetwork = null;
	Network network0 = manager.getActiveNetwork();

	if (network0 != null) {
	    NetworkCapabilities capabilities = manager.getNetworkCapabilities(network0);

	    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)) {
		priority = mManager.getMultipathPreference(network0);
		underlyingNetwork = network0;
	    }
	}

	for (Map.Entry<String, Network> entry : networkMap.entrySet()) {
	    Log.d(LOG_TAG, "key: " + entry.getKey() + " value: " + entry.getValue());

	    Network network = entry.getValue();
	    NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);

	    if (capabilities == null) {
		continue;
	    }

	    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)) {
		continue;
	    }

	    int preference = mManager.getMultipathPreference(network);
	    if (preference > priority) {
		underlyingNetwork = network;
		priority = preference;
	    }
	}

	return underlyingNetwork;
    }

    private int mLinkMtu = 1500;
    private int mPeerMtu = 1500;
    private int mHeadlen = 40 + 8 + 28 - 40;
    private static final int IPV4_HEADER_LENGTH = 20;
    private static final String[] GOOGLE_IPV4_ROUTES = {
	"8.8.4.0,24",
	"8.8.8.0,24",
	"64.233.160.0,19",
	"66.102.0.0,20",
	"66.249.64.0,19",
	"72.14.192.0,18",
	"74.125.0.0,16",
	"108.177.8.0,21",
	"108.177.96.0,19",
	"142.250.0.0,15",
	"172.217.0.0,16",
	"172.253.0.0,16",
	"173.194.0.0,16",
	"209.85.128.0,17",
	"216.58.192.0,19",
	"216.239.32.0,19"
    };

    public InetSocketAddress getDnsServer(boolean next) {
	InetSocketAddress defServer = null;
	Network currentNetwork = next? getUnderlyingNetwork(mManager): mManager.getActiveNetwork();

	try {
	    defServer = new InetSocketAddress(InetAddress.getByAddress(new byte[] {(byte)119, 29, 29, 29}), 53);
	} catch (Exception exception) {
	}

	if (currentNetwork == null) {
	    return defServer;
	}

	LinkProperties linkProperties = mManager.getLinkProperties(currentNetwork);
	if (linkProperties == null) {
	    return defServer;
	}

	int linkMtu = linkProperties.getMtu();
	if (linkMtu > 0)
		mLinkMtu = linkMtu;

	List<InetAddress> servers = linkProperties.getDnsServers();
	if (servers == null) {
	    return defServer;
	}

	String hostAddress = null;
	for (InetAddress server: servers) {
	    if (server instanceof Inet4Address) {
		hostAddress = server.getHostAddress();
		return new InetSocketAddress(hostAddress, 53);
	    }
	}

	return defServer;
    }

    @Override
    public void run() {
	try {
	    Log.i(getTag(), "Thread starting");
	    synchronized (mService) {
		if (mOnConnectListener != null) {
		    mOnConnectListener.onConnectStage(OnConnectListener.Stage.taskLaunch);
		}
	    }

	    // If anything needs to be obtained using the network, get it now.
	    // This greatly reduces the complexity of seamless handover, which
	    // tries to recreate the tunnel without shutting down everything.
	    // In this demo, all we need to know is the server address.
	    final SocketAddress serverAddress = new InetSocketAddress(mServerName, mServerPort);

	    // We try to create the tunnel several times.
	    // TODO: The better way is to work with ConnectivityManager, trying only when the
	    //       network is available.
	    // Here we just use a counter to keep things simple.
	    for (int attempt = 0; attempt < 3; ++attempt) {
		// Reset the counter if we were connected.
		if (run(serverAddress)) {
		    attempt = 0;
		}

		// Sleep for a while. This also checks if we got interrupted.
		Thread.sleep(3000);
	    }
	    Log.i(getTag(), "Giving up");
	} catch (InterruptedException | IllegalArgumentException | IllegalStateException e) {
	    Log.e(getTag(), "Connection failed, exiting", e);
	} finally {
	    Log.i(getTag(), "Thread dying");
	    synchronized (mService) {
		if (mOnConnectListener != null) {
		    mOnConnectListener.onConnectStage(OnConnectListener.Stage.taskTerminate);
		}
	    }
	}
    }

    private static final int IPV6_HEADER_LENGTH = 40;  // IPv6头部长度
    private static final int UDP_HEADER_LENGTH = 8;    // UDP头部长度
    private static final int DNS_A_RECORD = 1;         // A记录的类型码
    private static final int DNS_CLASS_IN = 1;
    private static final int DNS_PORT = 53;

    public static boolean isDnsPacket(ByteBuffer query, boolean isFallback) {
	if (query == null || query.remaining() < IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH) {
	    return false;
	}

	int position = query.position();
	query.position(IPV6_HEADER_LENGTH + query.position());

	int udpSrcPort = query.getShort() & 0xFFFF;
	int udpDstPort = query.getShort() & 0xFFFF;

	if (isFallback? udpSrcPort != DNS_PORT: udpDstPort != DNS_PORT) {
	    query.position(position);
	    return false;
	}

	int udpLength = query.getShort() & 0xFFFF;

	if (query.remaining() + 6 < udpLength) {
	    query.position(position);
	    return false;
	}

	query.getShort(); // checksum
			  // query.position(query.position() + UDP_HEADER_LENGTH);

	int transactionId = query.getShort() & 0xFFFF;
	int flags = query.getShort() & 0xFFFF;
	int questions = query.getShort() & 0xFFFF;
	int answerRRs = query.getShort() & 0xFFFF;
	int authorityRRs = query.getShort() & 0xFFFF;
	int additionalRRs = query.getShort() & 0xFFFF;

	if (questions == 0 || 0x8000 == (flags & 0x8000)) {
	    query.position(position);
	    return false;
	}

	while (query.remaining() > 0) {
	    byte length = query.get();
	    if (length == 0) {
		break;
	    }
	    query.position(query.position() + length);
	}

	int qType = query.getShort() & 0xFFFF;
	int qClass = query.getShort() & 0xFFFF;

	// Log.i(LOG_TAG, "isDnsPacket: " + (qType == DNS_A_RECORD));
	query.position(position);
	if (isFallback) return true;
	return qType == DNS_A_RECORD;
    }

    public static ByteBuffer generateDnsResponse(ByteBuffer query, int length, boolean isFallback) {
	if (query == null || query.remaining() < 12) {
	    throw new IllegalArgumentException("Invalid DNS request buffer");
	}

	ByteBuffer response = ByteBuffer.allocate(MAX_PACKET_SIZE);
	response.put(query.slice());
	response.flip();
	Log.d(LOG_TAG, "generateDnsResponse limit=" + response.limit() + " length=" + length + " limit=" + query.limit());

	byte[] src = new byte[16];
	byte[] dst = new byte[16];

	response.position(8);
	response.get(src, 0, 16);
	response.get(dst, 0, 16);

	byte[] srcPort = new byte[2];
	byte[] dstPort = new byte[2];
	response.get(srcPort, 0, 2);
	response.get(dstPort, 0, 2);

	response.position(8);
	if (isFallback) {
		response.put(src);
		response.put(dst);
		response.put(srcPort);
		response.put(dstPort);
	} else {
		response.put(dst);
		response.put(src);
		response.put(dstPort);
		response.put(srcPort);
	}

	short flags = response.getShort(50);
	response.putShort(50, (short)0x8180);

	short checksum = response.getShort(46);
	int newchecksum = (checksum & 0xffff) + (flags) + (0xffff & ~0x8180);
	while ((newchecksum >> 16) > 0)
	    newchecksum = (newchecksum >> 16) + (newchecksum & 0xffff);
	response.putShort(46, (short)newchecksum);

	response.position(0);
	return response;
    }

    static HashMap<Integer, byte[]> mDnsHeaderMap = new HashMap<>();
    private void dnsCachePrepareHeader(ByteBuffer packet) {
	int xid = (0xffff & packet.getShort(40 + 8));
	byte[] header = new byte[40 + 8 + 2];
	packet.position(0);
	packet.get(header);
	mDnsHeaderMap.put(xid, header);

	Log.d(LOG_TAG, "dnsCachePrepareHeader xid=" + xid);
    }

    ByteBuffer assembleDnsPacket(ByteBuffer packet, int length) {
	int xid = (0xffff & packet.getShort(0));
	ByteBuffer newPacket = ByteBuffer.allocate(length + 48);

	Log.d(LOG_TAG, "assembleDnsPacket xid=" + xid + " length=" + length);
	if (!mDnsHeaderMap.containsKey((Integer)xid)) {
	    Log.d(LOG_TAG, "assembleDnsPacket failure xid=" + xid);
	    return null;
	}
	newPacket.put(mDnsHeaderMap.get(xid));
	// newPacket.position(48);
	// packet.rewind();
	packet.position(2);
	packet.limit(length);
	newPacket.put(packet); 
	newPacket.flip();
	newPacket.putShort(44, (short)(length + 8));
	newPacket.putShort(4, (short)(length + 8));

	int sum = 0;
	newPacket.position(8);
	while (newPacket.remaining() > 1) {
	    sum += (0xffff & newPacket.getShort());
	}

	if (newPacket.remaining() > 0) {
	    int val = (0xff & newPacket.get());
	    sum += (val << 8);
	}

	sum += (length + 8);
	sum += newPacket.get(6);
	while ((sum >> 16) > 0)
	    sum = (sum >> 16) + (sum & 0xffff);

	int check = (newPacket.getShort(46) & 0xffff) + (0xffff & ~sum);

	while ((check >> 16) > 0)
	    check = (check >> 16) + (check & 0xffff);
	newPacket.putShort(46, (short)check);
	newPacket.rewind();

	return newPacket;
    }

    private static boolean isIpv4Local(byte[] address) {
	int first = address[0] & 0xff;
	int second = address[1] & 0xff;
	return first == 10 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168);
    }

    private static short ipv4HeaderChecksum(ByteBuffer packet, int offset) {
	int sum = 0;
	for (int i = 0; i < IPV4_HEADER_LENGTH; i += 2) {
	    if (i == 10) {
		continue;
	    }
	    sum += packet.getShort(offset + i) & 0xffff;
	}
	while ((sum >> 16) != 0) {
	    sum = (sum & 0xffff) + (sum >> 16);
	}
	return (short)~sum;
    }

    static class DatagramNetworkChannel {
	DatagramChannel dataChannel = null;
	ParcelFileDescriptor descriptor = null;

	public static DatagramNetworkChannel build(VpnService service, SocketAddress target) throws IOException {
	    DatagramChannel tunnel = DatagramChannel.open();
	    tunnel.setOption(StandardSocketOptions.SO_SNDBUF, 1024 * 1024);
	    tunnel.setOption(StandardSocketOptions.SO_RCVBUF, 1024 * 1024);

	    if (!service.protect(tunnel.socket())) {
		throw new IllegalStateException("Cannot protect the tunnel");
	    }

	    tunnel.connect(target);
	    tunnel.configureBlocking(false);
	    return new DatagramNetworkChannel(tunnel);
	}

	StructPollfd pollFd = new StructPollfd();
	DatagramNetworkChannel(DatagramChannel channel) {
	    dataChannel = channel;
	    descriptor  = ParcelFileDescriptor.fromDatagramSocket(channel.socket());

	    pollFd.events = (short)OsConstants.POLLIN;
	    pollFd.fd = descriptor.getFileDescriptor();
	}

	public StructPollfd fillPollfd() {
	    return pollFd;
	}

	public SocketAddress getLocalAddress() throws IOException {
	    return dataChannel.getLocalAddress();
	}

	public void close() throws IOException {
	    dataChannel.close();
	    descriptor.close();
	}

	public int receive(ByteBuffer packet) throws IOException {
	    return dataChannel.read(packet);
	}

	public int send(ByteBuffer packet) throws IOException {
	    return dataChannel.write(packet);
	}

	byte[] source = new byte[16];
	static final byte[] myaddr6 = {0x34, 2, 0x52, (byte)0xe2, 0x76, (byte)0xb5, 0, 0, 0, 0, (byte)0x5e, (byte)0xfe, 10, 63, (byte)249, 107};
	static int ipv4Ident = 0;
	public int read(ByteBuffer packet) throws IOException {
	    int reserve = 40;

	    packet.position(reserve);
	    int length = dataChannel.read(packet);
	    if (length < 24) {
		packet.clear();
		return 0;
	    }

	    packet.flip();
	    packet.position(packet.limit() - 4);
	    byte version = packet.get();
	    byte proto   = packet.get();
	    short plen   = packet.getShort();
	    int payloadLength = plen & 0xffff;

	    if (version == (byte)0xb7 || version == 0x48 || version == 0x40 || version == (byte)0xbf) {
		boolean encrypted = version == (byte)0xb7 || version == (byte)0xbf;
		byte[] sourceAddr = new byte[4];
		byte[] destinationAddr = new byte[4];

		packet.position(reserve);
		packet.get(destinationAddr);
		packet.position(packet.limit() - 8);
		packet.get(sourceAddr);

		packet.position(packet.limit() - payloadLength - IPV4_HEADER_LENGTH - 8);
		packet.mark();
		packet.put((byte)0x45);
		packet.put((byte)0);
		packet.putShort((short)(payloadLength + IPV4_HEADER_LENGTH));
		packet.putShort((short)(ipv4Ident++));
		packet.putShort((short)(proto == 6 ? 0x4000 : 0));
		packet.put((byte)0xff);
		packet.put(proto);
		packet.putShort((short)0);
		packet.put(sourceAddr);
		packet.put(destinationAddr);
		int headerStart = packet.position() - IPV4_HEADER_LENGTH;
		packet.putShort(headerStart + 10, ipv4HeaderChecksum(packet, headerStart));
		int base = packet.position();

		if (encrypted) {
		    for (int i = 0; i < payloadLength; i++) {
			byte code = packet.get(i + base);
			packet.put(i + base, (byte)(code ^ 0x0f));
		    }
		}

		packet.reset();
		packet.limit(base + payloadLength);
		return packet.limit();
	    } else if (version == (byte)0x97) {
		packet.position(packet.limit() - 20);
		packet.get(source);

		// packet.rewind();
		packet.position(packet.limit() - plen - 60);
		packet.mark();
		packet.putInt(0x60000000);
		packet.putShort(plen);
		packet.put(proto);
		packet.put((byte)0xff);
		packet.put(source);
		packet.put(myaddr6);
		int base = packet.position();

		for (int i = 0; i < plen; i++) {
			byte code = packet.get(i + base);
			packet.put(i + base, (byte)(code ^  0x0f));
		}

		packet.reset();
		packet.limit(base + plen);

		// packet.compact();
		return packet.limit();

	    } else if (version == 0x68) {
		packet.position(packet.limit() - 20);
		packet.get(source);

		// packet.rewind();
		packet.position(packet.limit() - plen - 60);
		packet.mark();
		packet.putInt(0x60000000);
		packet.putShort(plen);
		packet.put(proto);
		packet.put((byte)0xff);
		packet.put(source);
		packet.put(myaddr6);
		// packet.rewind();
		int base = packet.position();
		packet.reset();
		packet.limit(base + plen);

		// packet.compact();
		return packet.limit();
	    }

	    packet.clear();
            return 0;
        }

	byte[] destination = new byte[16];
	byte[] source4 = new byte[4];
	byte[] destination4 = new byte[4];
        public int write(ByteBuffer packet) throws IOException {
	    int position = packet.position();

            if (packet.remaining() < IPV4_HEADER_LENGTH) {
                packet.clear();
                return 0;
            }

            byte version = packet.get();
            if ((0xf0 & version) == 0x40) {
		packet.position(position + 2);
		short totalLength = packet.getShort();
		int totalLengthUnsigned = totalLength & 0xffff;
		packet.position(position + 9);
		byte proto = packet.get();
		packet.position(position + 12);
		packet.get(source4);
		packet.get(destination4);

		int headerLength = (version & 0x0f) * 4;
		if (headerLength < IPV4_HEADER_LENGTH || packet.limit() - position < headerLength) {
		    packet.clear();
		    return 0;
		}

		int base = position + headerLength;
		if (packet.limit() - position < totalLengthUnsigned || totalLengthUnsigned < headerLength + 4) {
		    packet.clear();
		    return 0;
		}
		packet.position(base);
		packet.getShort();
		short dport = packet.getShort();
		int plen = totalLengthUnsigned - headerLength;
		if (plen < 0) {
		    packet.clear();
		    return 0;
		}
		byte tagid = 0x40;
		boolean encrypted = false;
		if (proto == 17 && dport == (short)53) {
		    tagid = (byte)0xbf;
		    encrypted = true;
		} else if (proto == 6 && dport == (short)443) {
		    tagid = (byte)0xbf;
		    encrypted = true;
		} else if (proto == 6 && dport == (short)80) {
		    tagid = (byte)0xbf;
		    encrypted = true;
		} else if (proto == 58) {
		    tagid = (byte)0xbf;
		    encrypted = true;
		}

		byte[] srcToSend = source4;
		byte[] dstToSend = destination4;
		if (isIpv4Local(destination4)) {
		    tagid ^= 0x8;
		    srcToSend = destination4;
		    dstToSend = source4;
		}

		packet.position(packet.limit());
		packet.limit(packet.limit() + 8);
		packet.put(dstToSend);
		packet.put(tagid);
		packet.put(proto);
		packet.putShort((short)plen);
		packet.flip();

		if (encrypted) {
		    for (int i = 0; i < plen; i++) {
			byte code = packet.get(base + i);
			packet.put(base + i, (byte)(code ^ 0xf));
		    }
		}

		packet.position(base - 4);
		packet.mark();
		packet.put(srcToSend);
		packet.reset();

		return dataChannel.write(packet);
            } else if ((0xf0 & version) == 0x60) {
                // int length = packet.limit();
                // packet.limit(length + 20);
                packet.position(position + 4);

                short plen = packet.getShort();
                byte proto = packet.get();
                byte hop   = packet.get();

                packet.get(source);

                packet.get(destination);

                int base = packet.position();
		packet.getShort();
                short dport = packet.getShort();


		byte tagid = 0x60;
                if (proto == 17 && dport == (short)53) {
		    tagid = (byte)0x9f;
                } else if (proto == 6 && dport == (short)443) {
		    tagid = (byte)0x9f;
                } else if (proto == 6 && dport == (short)80) {
		    tagid = (byte)0x9f;
		} else if (proto == 58) {
		    tagid = (byte)0x9f;
		}

		packet.position(packet.limit());
		packet.limit(packet.limit() + 20);
		packet.put(destination);
		packet.put(tagid);
		packet.put(proto);
		packet.putShort(plen);
		packet.flip();

		int xorlen = (tagid == (byte)0x9f? plen: 0);
		for (int i = 0; i < xorlen; i++) {
		    byte code = packet.get(base + i);
		    packet.put(base + i, (byte)(code ^ 0xf));
		}

		packet.position(base - 8);
		packet.mark();
		packet.put(source, 8, 8);
		packet.reset();

                return dataChannel.write(packet);
            }

            int count = dataChannel.write(packet);
            return count;
        }
    };

    private boolean checkNetworkChange(VpnService service, SocketAddress target, DatagramNetworkChannel oldtunnel) throws IOException {

	if (networkChange) {
	    DatagramChannel tunnel = DatagramChannel.open();

	    if (!service.protect(tunnel.socket())) {
		throw new IllegalStateException("Cannot protect the tunnel");
	    }

	    tunnel.connect(target);
	    SocketAddress newValue = tunnel.getLocalAddress();
	    SocketAddress oldValue = oldtunnel.getLocalAddress();
	    tunnel.close();

	    if ((newValue instanceof InetSocketAddress) && (oldValue instanceof InetSocketAddress)) {
		    InetSocketAddress new4Value = (InetSocketAddress) newValue;
		    InetSocketAddress old4Value = (InetSocketAddress) oldValue;
		    networkChange = !new4Value.getAddress().equals(old4Value.getAddress());
	    }
	}

	return networkChange;
    }

    private boolean run(SocketAddress server)
	    throws InterruptedException, IllegalArgumentException, IllegalStateException {
	DatagramNetworkChannel tunnel = null;
	DatagramNetworkChannel dnsclient = null;
	DatagramNetworkChannel tcpclient = null;
	DatagramNetworkChannel udpclient = null;
	ParcelFileDescriptor iface = null;
	boolean success = false;
	try {
	    synchronized (mService) {
		if (mOnConnectListener != null) {
		    mOnConnectListener.onConnectStage(OnConnectListener.Stage.connecting);
		}
	    }

	    tunnel = DatagramNetworkChannel.build(mService, server);
	    tcpclient = DatagramNetworkChannel.build(mService, server);
	    udpclient = DatagramNetworkChannel.build(mService, server);
	    dnsclient = DatagramNetworkChannel.build(mService, getDnsServer(false));

	    // Authenticate with server and configure the virtual network interface.
	    //3402:52e2:76b5::5efe:c0a8:a8b/64
	    String dnsAddress = TextUtils.isEmpty(mDnsServer) ? "64:ff9b::7f08:808" : mDnsServer;
	    String parameters = "address,3402:52e2:76b5::5efe:10.63.249.107,64 dns," + dnsAddress; // handshakeServer(tunnel);
	    // String parameters = "address,3402:52e2:76b5::5efe:10.101.0.10,64 dns,64:ff9b::7f09:909"; // handshakeServer(tunnel);
	    parameters += " address,10.63.249.107,30";
	    parameters += " address,114.114.114.114,32";
	    parameters += " address,114.114.114.115,32";
	    parameters += " address,180.76.76.76,32";
	    parameters += " address,223.5.5.5,32";
	    for (String route : GOOGLE_IPV4_ROUTES) {
		parameters += " route," + route;
	    }
	    parameters += " route,64:ff9b::,64";
	    parameters += " route,2000::,48";
	    parameters += " route,2001:4860:4860::,48";

	    final String routes[] = {
		    "2000::/16",
		    "2003::/16",
		    "2004::/14",
		    "2008::/13",
		    "2010::/12",
		    "2020::/11",
		    "2040::/10",
		    "2080::/9",
		    "2100::/8",
		    "2200::/7",
		    "2410::/12",
		    "2420::/11",
		    "2440::/10",
		    "2480::/9",
		    "2500::/8",
		    "2600::/7",
		    "2800::/5",
		    "3000::/4"
	    };
	    for (String item: routes) parameters += " route," + item.replaceFirst("/", ",");


	    parameters += " mtu," + String.valueOf(mLinkMtu - mHeadlen);
	    Log.i(getTag(), "config (" + parameters + ")");

	    iface = configureVirtualInterface(parameters);
	    mPeerMtu = mLinkMtu;
	    Log.i(getTag(), "New interface: " + iface + " (" + parameters + ")");

	    synchronized (mService) {
		if (mOnConnectListener != null) {
		    mOnConnectListener.onConnectStage(OnConnectListener.Stage.establish);
		}
	    }

	    // Now we are connected. Set the flag.
	    success = true;

	    // Packets to be sent are queued in this input stream.
	    FileInputStream ifaceIn = new FileInputStream(iface.getFileDescriptor());
	    FileChannel ifaceInChannel = ifaceIn.getChannel();

	    // Packets received need to be written to this output stream.
	    FileOutputStream ifaceOut = new FileOutputStream(iface.getFileDescriptor());
	    FileChannel ifaceChannel = ifaceOut.getChannel();

	    // Allocate the buffer for a single packet.
	    ByteBuffer packet = ByteBuffer.allocateDirect(MAX_PACKET_SIZE);

	    // Timeouts:
	    //   - when data has not been sent in a while, send empty keepalive messages.
	    //   - when data has not been received in a while, assume the connection is broken.
	    long lastReadServerTime = System.currentTimeMillis();
	    long lastReadVirtualInterfaceTime = System.currentTimeMillis();

	    NetworkRequest networkRequest = new NetworkRequest.Builder()
		.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
		.addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
		.build();
	    mManager.registerNetworkCallback(networkRequest, mCallback);

	    StructPollfd pollIface = new StructPollfd();
	    pollIface.events = (short)OsConstants.POLLIN;
	    pollIface.fd = iface.getFileDescriptor();
	    StructPollfd[] structPollfds = new StructPollfd[5];

	    // We keep forwarding packets till something goes wrong.
	    //noinspection InfiniteLoopStatement
	    while (true) {
		// Assume that we did not make any progress in this iteration.
		boolean idle = true;
		boolean uploading = true;
		final int headspace = 8;

		// Read the outgoing packet from the input stream (Virtual Interface).
		packet.position(headspace);
		int length = ifaceInChannel.read(packet);
		while (length > 0) {
		    packet.flip();
		    packet.position(headspace);

		    if (isDnsPacket(packet, false)) {
			ByteBuffer newPacket = generateDnsResponse(packet, length, false);
			dnsCachePrepareHeader(newPacket);
			packet.position(packet.position() + 48);
			// ifaceOut.write(newPacket.array(), 0, newPacket.limit());
			dnsclient.send(packet);
			packet.clear();
		    } else {
			// Write the outgoing packet to the tunnel (server).
			// packet.position(length + 4);
			// byte val = packet.get(4 + 6);
			// packet.put(4 + 6, (byte)(val ^ (byte)0x5a));
			//
			byte val = packet.get(headspace + 6);
			switch (val) {
			    case 6:
				uploading = (length > 512);
				tcpclient.write(packet);
				break;

			    case 17:
				if (packet.getShort(headspace + 40 + 2) != 53) {
				    udpclient.write(packet);
				    break;
				}

			    default:
				tunnel.write(packet);
				break;
			}
			packet.clear();
			if (length > 60)
			    lastReadVirtualInterfaceTime = System.currentTimeMillis();
		    }

		    // There might be more outgoing packets.
		    if (uploading) {
			packet.position(headspace);
			length = ifaceInChannel.read(packet);
			uploading = false;
		    } else {
			idle = false;
			length = 0;
		    }
		}

		// Read the incoming packet from the tunnel (server).
		length = tunnel.read(packet);
		if (length > 0) {
		    if (isDnsPacket(packet, true)) {
			ByteBuffer newPacket = generateDnsResponse(packet, length, true);
			dnsCachePrepareHeader(newPacket);
			packet.position(packet.position() + 48);
			dnsclient.send(packet);
		    } else

		    // Ignore control messages, which start with zero.
		    if (packet.get(packet.position()) != 0) {
			ifaceChannel.write(packet);
		    } else {
			// response to remote server with idle packet immediately.
			tunnel.send(packet);
		    }
		    packet.clear();

		    // There might be more incoming packets.
		    idle = false;
		    lastReadServerTime = System.currentTimeMillis();
		}

		length = udpclient.read(packet);
		if (length > 0) {
		    ifaceChannel.write(packet);
		    packet.clear();

		    idle = false;
		    lastReadServerTime = System.currentTimeMillis();
		}

		length = tcpclient.read(packet);
		while (length > 0) {
		    ifaceChannel.write(packet);
		    packet.clear();

		    idle = false;
		    lastReadServerTime = System.currentTimeMillis();
		    length = tcpclient.read(packet);
		}

		length = dnsclient.receive(packet);
		if (length > 0) {
		    packet.flip();
		    Log.d(LOG_TAG, "dnsclient position=" + packet.position() + " length=" + packet.limit() + " length=" + length);
		    ByteBuffer dnsPacket = assembleDnsPacket(packet, length);
		    if (dnsPacket != null) {
			ifaceChannel.write(dnsPacket);
		    }

		    packet.clear();

		    // ifaceOut.write(packet.array(), 4, length - 4);
		    idle = false;
		}

		if (idle && checkNetworkChange(mService, server, tunnel)) {
		    networkChange = false;
		    tcpclient.close();
		    udpclient.close();
		    dnsclient.close();
		    tunnel.close();

		    tunnel = DatagramNetworkChannel.build(mService, server);
		    tcpclient = DatagramNetworkChannel.build(mService, server);
		    udpclient = DatagramNetworkChannel.build(mService, server);
		    dnsclient = DatagramNetworkChannel.build(mService, getDnsServer(true));
		    if (mLinkMtu != mPeerMtu)
			throw new IOException("Timed out 0");
		} else if (idle) {
		    int nPolled = 0;
		    structPollfds[0] = tunnel.fillPollfd();
		    structPollfds[1] = dnsclient.fillPollfd();
		    structPollfds[2] = tcpclient.fillPollfd();
		    structPollfds[3] = udpclient.fillPollfd();
		    structPollfds[4] = pollIface;

		    //noinspection BusyWait
		    try {

			nPolled = Os.poll(structPollfds, 1000);
			// if ((pollTunnel.revents & OsConstants.POLLIN) == OsConstants.POLLIN) lastReadServerTime = System.currentTimeMillis();
			if (nPolled > 0) lastReadServerTime = System.currentTimeMillis();
		    } catch (ErrnoException e) {
			throw new IOException("Timed out 0");
		    }

		    long timeNow = System.currentTimeMillis();

		    if (lastReadVirtualInterfaceTime > lastReadServerTime && lastReadServerTime + RECEIVE_TIMEOUT_MS <= timeNow) {
			lastReadServerTime = System.currentTimeMillis();
			lastReadVirtualInterfaceTime = System.currentTimeMillis();

			try {
			    nPolled = Os.poll(structPollfds, 3600000);
			    if (nPolled == 0) networkChange = true;
			} catch (ErrnoException e) {
			    throw new IOException("Timed out 1");
			}
		    }

		    if (lastReadServerTime + KEEPALIVE_INTERVAL_MS <= timeNow) {
			// We are receiving for a long time but not sending.
			// Send empty control messages.
			packet.put((byte) 0).limit(1);
			packet.position(0);
			// tunnel.write(packet);
			packet.clear();
		    }
		}
	    }
	} catch (PortUnreachableException e) {
	    success = false;
	    Log.e(getTag(), "Cannot use socket for PortUnreachableException", e);
	} catch (IOException e) {
	    Log.e(getTag(), "Cannot use socket", e);
	} finally {

	    synchronized (mService) {
		if (mOnConnectListener != null) {
		    mOnConnectListener.onConnectStage(OnConnectListener.Stage.disconnected);
		}
	    }

	    try {
		mManager.unregisterNetworkCallback(mCallback);

		if (iface != null) {
		    iface.close();
		}

		if (dnsclient != null) {
		    dnsclient.close();
		}

		if (tcpclient != null) {
		    tcpclient.close();
		}

		if (udpclient != null) {
		    udpclient.close();
		}

		if (tunnel != null) {
		    tunnel.close();
		}
	    } catch (Exception e) {
		Log.e(getTag(), "Unable to close interface", e);
	    }
	}
	return success;
    }

    private String handshakeServer(DatagramChannel tunnel)
	    throws IOException, InterruptedException {
	    // To build a secured tunnel, we should perform mutual authentication
	// and exchange session keys for encryption. To keep things simple in
    // this demo, we just send the shared secret in plaintext and wait
// for the server to send the parameters.

	// Allocate the buffer for handshaking. We have a hardcoded maximum
    // handshake size of 1024 bytes, which should be enough for demo
// purposes.
	    ByteBuffer packet = ByteBuffer.allocate(1024);

	    // Control messages always start with zero.
	    packet.put((byte) 0).put(mSharedSecret).flip();

	    // Send the secret several times in case of packet loss.
	    for (int i = 0; i < 3; ++i) {
		packet.position(0);
		tunnel.write(packet);
	    }
	    packet.clear();

	    // Wait for the parameters within a limited time.
	    for (int i = 0; i < MAX_HANDSHAKE_ATTEMPTS; ++i) {
		Thread.sleep(IDLE_INTERVAL_MS);

		// Normally we should not receive random packets. Check that the first
		// byte is 0 as expected.
		int length = tunnel.read(packet);
		if (length > 0 && packet.get(0) == 0) {
		    return new String(packet.array(), 1, length - 1, US_ASCII).trim();
		}
	    }
	    throw new IOException("Timed out");
    }

    private ParcelFileDescriptor configureVirtualInterface(String parameters)
	    throws IllegalArgumentException {
	    // Configure a builder while parsing the parameters.
	    VpnService.Builder builder = mService.new Builder();
	    for (String parameter : parameters.split(" ")) {
		String[] fields = parameter.split(",");
		try {
		    switch (fields[0].charAt(0)) {
			case 'm':
			    builder.setMtu(Short.parseShort(fields[1]));
			    break;
			case 'a':
			    builder.addAddress(fields[1], Integer.parseInt(fields[2]));
			    break;
			case 'r':
			    builder.addRoute(fields[1], Integer.parseInt(fields[2]));
			    break;
			case 'd':
			    builder.addDnsServer(fields[1]);
			    break;
			case 's':
			    builder.addSearchDomain(fields[1]);
			    break;
		    }
		} catch (NumberFormatException e) {
		    throw new IllegalArgumentException("Bad parameter: " + parameter);
		}
	    }
	    for (String packageName : mPackages) {
		try {
		    if (mAllow) {
			builder.addAllowedApplication(packageName);
		    } else {
			builder.addDisallowedApplication(packageName);
		    }
		} catch (PackageManager.NameNotFoundException e){
		    Log.w(getTag(), "Package not available: " + packageName, e);
		}
	    }
	    builder.setSession(mServerName).setConfigureIntent(mConfigureIntent);
	    if (!TextUtils.isEmpty(mProxyHostName)) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
		    builder.setHttpProxy(ProxyInfo.buildDirectProxy(mProxyHostName, mProxyHostPort));
		}
	    }

	    // Create a new interface using the builder and save the parameters.
	    return builder.establish();
    }

    private String getTag() {
	return ToyVpnRunnable.class.getSimpleName() + "_" + mConnectionId;
    }
}
