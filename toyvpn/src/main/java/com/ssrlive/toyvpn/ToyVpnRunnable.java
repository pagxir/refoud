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

package com.ssrlive.toyvpn;

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
import java.net.Inet4Address;
import android.net.NetworkCapabilities;
import java.nio.channels.SelectableChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
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
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;

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
    private final static String LOG_TAG = "HELLO";

    // Proxy settings
    private String mProxyHostName;
    private int mProxyHostPort;

    // Allowed/Disallowed packages for VPN usage
    private final boolean mAllow;
    private final Set<String> mPackages;

    public ToyVpnRunnable(final VpnService service, final int connectionId,
                          final String serverName, final int serverPort, final byte[] sharedSecret,
                          final String proxyHostName, final int proxyHostPort, boolean allow,
                          final Set<String> packages) {
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

    Map<String, Network> networkMap = new HashMap<>();
    final ConnectivityManager.NetworkCallback mCallback = new ConnectivityManager.NetworkCallback() {

        @Override
        public void onAvailable(Network network) {
            super.onAvailable(network);
            String netId = network.toString();
            Log.d(LOG_TAG, "NetworkStateCallback.onAvailable " + netId);

            networkMap.put(netId, network);
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

    public InetSocketAddress getDnsServer(boolean next) {
	InetSocketAddress defServer = null;
	Network currentNetwork = next? getUnderlyingNetwork(mManager): mManager.getActiveNetwork();

	try {
	    defServer = new InetSocketAddress(InetAddress.getByAddress(new byte[] {(byte)223, 5, 5, 5}), 53);
	} catch (Exception exception) {
	}

	if (currentNetwork == null) {
	    return defServer;
	}

	LinkProperties linkProperties = mManager.getLinkProperties(currentNetwork);
	if (linkProperties == null) {
	    return defServer;
	}

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
            for (int attempt = 0; attempt < 10; ++attempt) {
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

    public static boolean isDnsPacket(ByteBuffer query) {
        if (query == null || query.remaining() < IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH) {
            return false;
        }

        query.position(IPV6_HEADER_LENGTH + query.position());
        
        int udpSrcPort = query.getShort() & 0xFFFF;
        int udpDstPort = query.getShort() & 0xFFFF;
        
        if (udpDstPort != DNS_PORT) {
            return false;
        }
        
        int udpLength = query.getShort() & 0xFFFF;
        
        if (query.remaining() + 6 < udpLength) {
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

	// Log.i("HELLO", "isDnsPacket: " + (qType == DNS_A_RECORD));
        return qType == DNS_A_RECORD;
    }

    public static ByteBuffer generateDnsResponse(ByteBuffer query, int length) {
        if (query == null || query.remaining() < 12) {
            throw new IllegalArgumentException("Invalid DNS request buffer");
        }

        ByteBuffer response = ByteBuffer.allocate(MAX_PACKET_SIZE);
	response.put(query.slice());
	response.flip();
	Log.d("HELLO", "generateDnsResponse limit=" + response.limit() + " length=" + length + " limit=" + query.limit());

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
	response.put(dst);
	response.put(src);
	response.put(dstPort);
	response.put(srcPort);

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

	Log.d("HELLO", "dnsCachePrepareHeader xid=" + xid);
    }

    ByteBuffer assembleDnsPacket(ByteBuffer packet, int length) {
	int xid = (0xffff & packet.getShort(0));
	ByteBuffer newPacket = ByteBuffer.allocate(length + 48);

	Log.d("HELLO", "assembleDnsPacket xid=" + xid + " length=" + length);
	if (!mDnsHeaderMap.containsKey((Integer)xid)) {
		Log.d("HELLO", "assembleDnsPacket failure xid=" + xid);
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

/*
    memcpy(&target.sin_addr, packet, sizeof(target.sin_addr));
                tolen = sizeof(target);

                memcpy(flags, limited - sizeof(flags), sizeof(flags));
                size_t plen = flags[1];

                uint32_t check = checksum(packet + sizeof(ident), htons(plen));

                struct ip6_hdr *ip6 = (struct ip6_hdr *)(packet + sizeof(ident));
                ip6--;
                ip6->ip6_ver  = htonl(0x60000000);
                ip6->ip6_plen = plen;
                ip6->ip6_limit = 0xff;
                ip6->ip6_next  = htons(flags[0]);

*/
    static final byte[] myaddr6 = {0x34, 2, 0x52, (byte)0xe2, 0x76, (byte)0xb5, 0, 0, 0, 0, (byte)0x5e, (byte)0xfe, 10, 101, 0, 10};
    static int tunnelRead(DatagramChannel tunnel, ByteBuffer packet) throws IOException {
	packet.position(40);
	int length = tunnel.read(packet);
	if (length < 24) {
		packet.clear();
		return 0;
	}

	packet.flip();
	packet.position(length + 40 - 4);
	byte version = packet.get();
	byte proto   = packet.get();
	short plen   = packet.getShort();

	if (version == 0x68) {
		byte[] source = new byte[16];
		packet.position(length + 40 - 20);
		packet.get(source);

		packet.rewind();
		packet.putInt(0x280000);
		packet.putInt(0x60000000);
		packet.putShort(plen);
		packet.put(proto);
		packet.put((byte)0xff);
		packet.put(source);
		packet.put(myaddr6);
		packet.rewind();
		packet.limit(length + 40 - 20);

		// packet.compact();
		return packet.limit();
	}

	return length;
    }

    static int tunnelWrite(DatagramChannel tunnel, ByteBuffer packet) throws IOException {
	int length = packet.limit();

	if (length < 40) {
		packet.clear();
		return 0;
	}

	packet.rewind();
	byte version = packet.get(4);
	if ((0xf0&version) == 0x60) {
		packet.limit(length + 20);
		packet.position(8);

		short plen = packet.getShort();
		byte proto = packet.get();
		byte hop   = packet.get();

		byte[] source = new byte[16];
		packet.get(source);

		byte[] destination = new byte[16];
		packet.get(destination);

		packet.position(length);
		packet.put(destination);
		packet.put((byte)0x60);
		packet.put(proto);
		packet.putShort(plen);
		packet.flip();

		packet.position(40);
		packet.mark();
		packet.put(source, 12, 4);
		packet.reset();

		/*
		   ByteBuffer outbuffer = packet.compact();
		   outbuffer.flip();
		*/

		return tunnel.write(packet);
	}

	int count = tunnel.write(packet);
	return count;
    }

    private boolean run(SocketAddress server)
            throws InterruptedException, IllegalArgumentException, IllegalStateException {
        DatagramChannel tunnel = null;
        DatagramChannel dnsclient = null;
        ParcelFileDescriptor iface = null;
        boolean success = false;
        try {
            synchronized (mService) {
                if (mOnConnectListener != null) {
                    mOnConnectListener.onConnectStage(OnConnectListener.Stage.connecting);
                }
            }

            // Create a DatagramChannel as the VPN tunnel.
            tunnel = DatagramChannel.open();
	    tunnel.setOption(StandardSocketOptions.SO_SNDBUF, 1024 * 1024);
	    tunnel.setOption(StandardSocketOptions.SO_RCVBUF, 1024 * 1024);

            // Protect the tunnel before connecting to avoid loopback.
            if (!mService.protect(tunnel.socket())) {
                throw new IllegalStateException("Cannot protect the tunnel");
            }

            // Connect to the server.
            tunnel.connect(server);

            // For simplicity, we use the same thread for both reading and
            // writing. Here we put the tunnel into non-blocking mode.
            tunnel.configureBlocking(false);

            dnsclient = DatagramChannel.open();
            if (!mService.protect(dnsclient.socket())) {
                throw new IllegalStateException("Cannot protect the tunnel");
            }
            dnsclient.connect(getDnsServer(false));
	    dnsclient.configureBlocking(false);

            // Authenticate with server and configure the virtual network interface.
	    //3402:52e2:76b5::5efe:c0a8:a8b/64
            String parameters = "mtu,1400 address,3402:52e2:76b5::5efe:10.101.0.10,64 dns,64:ff9b::7f09:909"; // handshakeServer(tunnel);
	    parameters += " address,10.101.0.10,30";
	    parameters += " route,64:ff9b::,96";
	    parameters += " route,2000::,48";
	    parameters += " route,2001:4860:4860::,48";

            iface = configureVirtualInterface(parameters);
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

            // Packets received need to be written to this output stream.
            FileOutputStream ifaceOut = new FileOutputStream(iface.getFileDescriptor());

            // Allocate the buffer for a single packet.
            ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);

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

            // We keep forwarding packets till something goes wrong.
            //noinspection InfiniteLoopStatement
            while (true) {
                // Assume that we did not make any progress in this iteration.
                boolean idle = true;

                // Read the outgoing packet from the input stream (Virtual Interface).
                int length = ifaceIn.read(packet.array(), 4, MAX_PACKET_SIZE - 4);
                if (length > 0) {
		    packet.limit(length + 4);
		    packet.position(4);

		    if (isDnsPacket(packet)) {
			packet.position(4);
			ByteBuffer newPacket = generateDnsResponse(packet, length);
			dnsCachePrepareHeader(newPacket);
			packet.position(4 + 48);
			// ifaceOut.write(newPacket.array(), 0, newPacket.limit());
			dnsclient.write(packet);
			packet.clear();
		    } else {
			packet.position(0);
			// Write the outgoing packet to the tunnel (server).
			// packet.position(length + 4);
			// byte val = packet.get(4 + 6);
			// packet.put(4 + 6, (byte)(val ^ (byte)0x5a));
			tunnelWrite(tunnel, packet);
			packet.clear();
			if (length > 60)
			    lastReadVirtualInterfaceTime = System.currentTimeMillis();
		    }

                    // There might be more outgoing packets.
                    idle = false;
                }

                // Read the incoming packet from the tunnel (server).
                length = tunnelRead(tunnel, packet);
                if (length > 0) {
                    // Ignore control messages, which start with zero.
                    if (packet.get(4) != 0) {
			// byte val = packet.get(4 + 6);
			// packet.put(4 + 6, (byte)((val) ^ (byte)(0x5a)));
                        // Write the incoming packet to the output stream (Virtual Interface).
                        ifaceOut.write(packet.array(), 4, length - 4);
                    } else {
                        // response to remote server with idle packet immediately.
                        tunnel.write(packet);
                    }
                    packet.clear();

                    // There might be more incoming packets.
                    idle = false;
                    lastReadServerTime = System.currentTimeMillis();
                }

		length = dnsclient.read(packet);
		if (length > 0) {
		    packet.flip();
		    Log.d("HELLO", "dnsclient position=" + packet.position() + " length=" + packet.limit() + " length=" + length);
                    ByteBuffer dnsPacket = assembleDnsPacket(packet, length);
		    if (dnsPacket != null) {
			ifaceOut.write(dnsPacket.array(), 0, dnsPacket.limit());
		    }

                    packet.clear();

		    // ifaceOut.write(packet.array(), 4, length - 4);
		    idle = false;
		}

                // If we are idle or waiting for the network, sleep for a
                // fraction of time to avoid busy looping.
                if (idle) {
			ParcelFileDescriptor tunnelfd = ParcelFileDescriptor.fromDatagramSocket(tunnel.socket());
			ParcelFileDescriptor dnsclientfd = ParcelFileDescriptor.fromDatagramSocket(dnsclient.socket());

			StructPollfd pollTunnel = new StructPollfd();
			pollTunnel.events = (short)OsConstants.POLLIN;
			pollTunnel.fd = tunnelfd.getFileDescriptor();

			StructPollfd pollDns = new StructPollfd();
			pollDns.events = (short)OsConstants.POLLIN;
			pollDns.fd = dnsclientfd.getFileDescriptor();

			StructPollfd pollIface = new StructPollfd();
			pollIface.events = (short)OsConstants.POLLIN;
			pollIface.fd = iface.getFileDescriptor();

			//noinspection BusyWait
			try {
				int nPolled = Os.poll(new StructPollfd[]{pollTunnel, pollDns, pollIface}, 1000);
				if ((pollTunnel.revents & OsConstants.POLLIN) == OsConstants.POLLIN) lastReadServerTime = System.currentTimeMillis();
			} catch (ErrnoException e) {
				dnsclientfd.close();
				tunnelfd.close();
				throw new IOException("Timed out");
			}

			dnsclientfd.close();
			tunnelfd.close();
			final long timeNow = System.currentTimeMillis();

			if (lastReadVirtualInterfaceTime > lastReadServerTime && lastReadServerTime + RECEIVE_TIMEOUT_MS <= timeNow) {
				// We are sending for a long time but not receiving.
				lastReadVirtualInterfaceTime = System.currentTimeMillis();
				lastReadServerTime = System.currentTimeMillis();

				try {
					int nPolled = Os.poll(new StructPollfd[]{pollTunnel, pollDns, pollIface}, 3600000);
				} catch (ErrnoException e) {
				}

				tunnel.disconnect();
				tunnel.close();
				tunnel = DatagramChannel.open();
				tunnel.setOption(StandardSocketOptions.SO_SNDBUF, 1024 * 1024);
				tunnel.setOption(StandardSocketOptions.SO_RCVBUF, 1024 * 1024);

				// Protect the tunnel before connecting to avoid loopback.
				if (!mService.protect(tunnel.socket())) {
					throw new IllegalStateException("Cannot protect the tunnel");
				}

				// Connect to the server.
				tunnel.connect(server);

				// For simplicity, we use the same thread for both reading and
				// writing. Here we put the tunnel into non-blocking mode.
				tunnel.configureBlocking(false);

				dnsclient.disconnect();
				dnsclient.close();
				dnsclient = DatagramChannel.open();
				if (!mService.protect(dnsclient.socket())) {
					throw new IllegalStateException("Cannot protect the tunnel");
				}
				dnsclient.connect(getDnsServer(true));
				dnsclient.configureBlocking(false);

				// throw new IOException("Timed out");
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
                    dnsclient.disconnect();
		    dnsclient.close();
		}
                if (tunnel != null) {
                    tunnel.disconnect();
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
        return ToyVpnRunnable.class.getSimpleName() + "[" + mConnectionId + "]";
    }
}
