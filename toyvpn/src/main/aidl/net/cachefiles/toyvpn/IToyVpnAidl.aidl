package net.cachefiles.toyvpn;

interface IToyVpnAidl {
    void basicTypes(int anInt, long aLong, boolean aBoolean, float aFloat, double aDouble, String aString);
    void saveServer(String server, int port, String dns);
    boolean connected();
}
