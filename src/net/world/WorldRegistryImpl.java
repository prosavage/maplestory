package net.world;

import client.LoginCrypto;
import constants.ServerConfig;
import database.DatabaseConnection;
import java.rmi.ConnectException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import net.MaplePacket;
import net.channel.remote.ChannelWorldInterface;
import net.login.remote.LoginWorldInterface;
import net.world.guild.MapleAlliance;
import net.world.guild.MapleGuild;
import net.world.guild.MapleGuildCharacter;
import net.world.remote.WorldChannelInterface;
import net.world.remote.WorldLoginInterface;
import net.world.remote.WorldRegistry;

public class WorldRegistryImpl extends UnicastRemoteObject implements WorldRegistry {
    private AtomicInteger runningMessengerId = new AtomicInteger();
    private AtomicInteger runningPartyId = new AtomicInteger();
    private List<LoginWorldInterface> loginServer = new LinkedList<LoginWorldInterface>();
    private Map<Integer, ChannelWorldInterface> channelServer = new LinkedHashMap<Integer, ChannelWorldInterface>();
    private Map<Integer, MapleAlliance> alliances = new LinkedHashMap<Integer, MapleAlliance>();
    private Map<Integer, MapleGuild> guilds = new LinkedHashMap<Integer, MapleGuild>();
    private Map<Integer, MapleMessenger> messengers = new HashMap<Integer, MapleMessenger>();
    private Map<Integer, MapleParty> parties = new HashMap<Integer, MapleParty>();
    private PlayerBuffStorage buffStorage = new PlayerBuffStorage();
    private static WorldRegistryImpl instance = null;
    private static final long serialVersionUID = -5170574938159280746L;

    private WorldRegistryImpl() throws RemoteException {
        super(0, new SslRMIClientSocketFactory(), new SslRMIServerSocketFactory());
        Connection con = DatabaseConnection.getConnection();
        try {
            PreparedStatement ps = con.prepareStatement("SELECT MAX(party)+1 FROM characters");
            ResultSet rs = ps.executeQuery();
            rs.next();
            runningPartyId.set(rs.getInt(1));
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        runningMessengerId.set(1);
    }

    public static WorldRegistryImpl getInstance() {
        if (instance == null) {
            try {
                instance = new WorldRegistryImpl();
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
        return instance;
    }

    private int getFreeChannelId() {
        for (int i = 0; i < 30; i++) {
            if (!channelServer.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    public WorldChannelInterface registerChannelServer(String authKey, ChannelWorldInterface cb) throws RemoteException {
        for (ServerConfig.ChannelKeys sc : ServerConfig.ChannelKeys.values()) {
            if (sc.getValue().equals(LoginCrypto.hexSha1(authKey)) && sc.getWorld() == WorldServer.getInstance().getWorldId()) {
                int channelId = sc.getNumber();
                if (channelId < 1) {
                    channelId = getFreeChannelId();
                    if (channelId == -1) {
                        throw new RuntimeException("Maximum channels reached");
                    }
                } else {
                    if (channelServer.containsKey(channelId)) {
                        ChannelWorldInterface oldch = channelServer.get(channelId);
                        try {
                            oldch.shutdown(0);
                        } catch (ConnectException ce) {
                        }
                    }
                }
                channelServer.put(channelId, cb);
                cb.setChannelId(channelId);
                WorldChannelInterface ret = new WorldChannelInterfaceImpl(cb, sc.getNumber());
                return ret;
            }
        }
        throw new RuntimeException("Couldn't find a channel with the given key (" + authKey + ")");
    }

    public void deregisterChannelServer(int channel) throws RemoteException {
        channelServer.remove(channel);
        for (LoginWorldInterface wli : loginServer) {
            wli.channelOffline(channel);
        }
        System.out.println("Channel " + channel + " is offline.");
    }

    public WorldLoginInterface registerLoginServer(String authKey, LoginWorldInterface cb) throws RemoteException {
        WorldLoginInterface ret = null;
        for (ServerConfig.LoginServer sc : ServerConfig.LoginServer.values()) {
            if (sc.getValue().equals(LoginCrypto.hexSha1(authKey)) && sc.getWorld() == WorldServer.getInstance().getWorldId()) {
                loginServer.add(cb);
                for (ChannelWorldInterface cwi : channelServer.values()) {
                    cb.channelOnline(cwi.getChannelId(), authKey);
                }
            }
        }
        ret = new WorldLoginInterfaceImpl();
        return ret;
    }

    public void deregisterLoginServer(LoginWorldInterface cb) throws RemoteException {
        loginServer.remove(cb);
    }

    public List<LoginWorldInterface> getLoginServer() {
        return new LinkedList<LoginWorldInterface>(loginServer);
    }

    public ChannelWorldInterface getChannel(int channel) {
        return channelServer.get(channel);
    }

    public Set<Integer> getChannelServer() {
        return new HashSet<Integer>(channelServer.keySet());
    }

    public Collection<ChannelWorldInterface> getAllChannelServers() {
        return channelServer.values();
    }

    public int getHighestChannelId() {
        int highest = 0;
        for (Integer channel : channelServer.keySet()) {
            if (channel != null && channel.intValue() > highest) {
                highest = channel.intValue();
            }
        }
        return highest;
    }

    public MapleParty createParty(MaplePartyCharacter chrfor) {
        int partyid = runningPartyId.getAndIncrement();
        MapleParty party = new MapleParty(partyid, chrfor);
        parties.put(partyid, party);
        return party;
    }

    public MapleParty getParty(int partyid) {
        return parties.get(partyid);
    }

    public MapleParty disbandParty(int partyid) {
        return parties.remove(partyid);
    }

    public String getStatus() throws RemoteException {
        StringBuilder ret = new StringBuilder();
        List<Entry<Integer, ChannelWorldInterface>> channelServers = new ArrayList<Entry<Integer, ChannelWorldInterface>>(channelServer.entrySet());
        Collections.sort(channelServers, new Comparator<Entry<Integer, ChannelWorldInterface>>() {

            @Override
            public int compare(Entry<Integer, ChannelWorldInterface> o1, Entry<Integer, ChannelWorldInterface> o2) {
                return o1.getKey().compareTo(o2.getKey());
            }
        });
        int totalUsers = 0;
        for (Entry<Integer, ChannelWorldInterface> cs : channelServers) {
            ret.append("Channel ");
            ret.append(cs.getKey());
            try {
                cs.getValue().isAvailable();
                ret.append(": online, ");
                int channelUsers = cs.getValue().getConnected();
                totalUsers += channelUsers;
                ret.append(channelUsers);
                ret.append(" users\n");
            } catch (RemoteException e) {
                ret.append(": offline\n");
            }
        }
        ret.append("Total users online: ");
        ret.append(totalUsers);
        ret.append("\n");
        Properties props = new Properties(WorldServer.getInstance().getWorldProp());
        int loginInterval = Integer.parseInt(props.getProperty("login.interval"));
        for (LoginWorldInterface lwi : loginServer) {
            ret.append("Login: ");
            try {
                lwi.isAvailable();
                ret.append("online\n");
                ret.append("Users waiting in login queue: ");
                ret.append(lwi.getWaitingUsers());
                ret.append(" users\n");
                int loginMinutes = (int) Math.ceil((double) loginInterval * ((double) lwi.getWaitingUsers() / lwi.getPossibleLoginAverage())) / 60000;
                ret.append("Current average login waiting time: ");
                ret.append(loginMinutes);
                ret.append(" minutes\n");
            } catch (RemoteException e) {
                ret.append("offline\n");
            }
        }
        return ret.toString();
    }

    public int createGuild(int leaderId, String name) {
        return MapleGuild.createGuild(leaderId, name);
    }

    public MapleGuild getGuild(int id, MapleGuildCharacter mgc) {
        synchronized (guilds) {
            if (guilds.get(id) != null) {
                return guilds.get(id);
            }
            MapleGuild g = new MapleGuild(id, mgc);
            if (g.getId() == -1) {
                return null;
            }
            guilds.put(id, g);
            return g;
        }
    }

    public void clearGuilds() {
        synchronized (guilds) {
            guilds.clear();
        }
        try {
            for (ChannelWorldInterface cwi : this.getAllChannelServers()) {
                cwi.reloadGuildCharacters();
            }
        } catch (RemoteException re) {
            System.out.println("RemoteException occurred while attempting to reload guilds." + re);
        }
    }

    public void setGuildMemberOnline(MapleGuildCharacter mgc, boolean bOnline, int channel) {
        MapleGuild g = getGuild(mgc.getGuildId(), mgc);
        g.setOnline(mgc.getId(), bOnline, channel);
    }

    public int addGuildMember(MapleGuildCharacter mgc) {
        MapleGuild g = guilds.get(mgc.getGuildId());
        if (g != null) {
            return g.addGuildMember(mgc);
        }
        return 0;
    }

    public void leaveGuild(MapleGuildCharacter mgc) {
        MapleGuild g = guilds.get(mgc.getGuildId());
        if (g != null) {
            g.leaveGuild(mgc);
        }
    }

    public void guildChat(int gid, String name, int cid, String msg) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.guildChat(name, cid, msg);
        }
    }

    public void changeRank(int gid, int cid, int newRank) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.changeRank(cid, newRank);
        }
    }

    public void expelMember(MapleGuildCharacter initiator, String name, int cid) {
        MapleGuild g = guilds.get(initiator.getGuildId());
        if (g != null) {
            g.expelMember(initiator, name, cid);
        }
    }

    public void setGuildNotice(int gid, String notice) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.setGuildNotice(notice);
        }
    }

    public void memberLevelJobUpdate(MapleGuildCharacter mgc) {
        MapleGuild g = guilds.get(mgc.getGuildId());
        if (g != null) {
            g.memberLevelJobUpdate(mgc);
        }
    }

    public void changeRankTitle(int gid, String[] ranks) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.changeRankTitle(ranks);
        }
    }

    public void setGuildEmblem(int gid, short bg, byte bgcolor, short logo, byte logocolor) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.setGuildEmblem(bg, bgcolor, logo, logocolor);
        }
    }

    public void disbandGuild(int gid) {
        synchronized (guilds) {
            MapleGuild g = guilds.get(gid);
            g.disbandGuild();
            guilds.remove(gid);
        }
    }

    public boolean setGuildAllianceId(int gId, int aId) {
        MapleGuild guild = guilds.get(gId);
        if (guild != null) {
            guild.setAllianceId(aId);
            return true;
        }
        return false;
    }

    public boolean increaseGuildCapacity(int gid) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            return g.increaseCapacity();
        }
        return false;
    }

    public void gainGP(int gid, int amount) {
        MapleGuild g = guilds.get(gid);
        if (g != null) {
            g.gainGP(amount);
        }
    }

    public MapleMessenger createMessenger(MapleMessengerCharacter chrfor) {
        int messengerid = runningMessengerId.getAndIncrement();
        MapleMessenger messenger = new MapleMessenger(messengerid, chrfor);
        messengers.put(messenger.getId(), messenger);
        return messenger;
    }

    public MapleMessenger getMessenger(int messengerid) {
        return messengers.get(messengerid);
    }

    public PlayerBuffStorage getPlayerBuffStorage() {
        return buffStorage;
    }

    public MapleAlliance getAlliance(int id) {
        synchronized (alliances) {
            if (alliances.containsKey(id)) {
                return alliances.get(id);
            }
            return null;
        }
    }

    public void addAlliance(int id, MapleAlliance alliance) {
        synchronized (alliances) {
            if (!alliances.containsKey(id)) {
                alliances.put(id, alliance);
            }
        }
    }

    public void disbandAlliance(int id) {
        synchronized (alliances) {
            MapleAlliance alliance = alliances.get(id);
            if (alliance != null) {
                for (Integer gid : alliance.getGuilds()) {
                        MapleGuild guild = guilds.get(gid);
                        guild.setAllianceId(0);
                }
                alliances.remove(id);
            }
        }
    }

    public void allianceMessage(int id, MaplePacket packet, int exception, int guildex) {
        MapleAlliance alliance = alliances.get(id);
        if (alliance != null) {
            for (Integer gid : alliance.getGuilds()) {
                if (guildex == gid) {
                    continue;
                }
                MapleGuild guild = guilds.get(gid);
                if (guild != null) {
                    guild.broadcast(packet, exception);
                }
            }
        }
    }

    public boolean addGuildtoAlliance(int aId, int guildId) {
        MapleAlliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.addGuild(guildId);
            return true;
        }
        return false;
    }

    public boolean removeGuildFromAlliance(int aId, int guildId) {
        MapleAlliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.removeGuild(guildId);
            return true;
        }
        return false;
    }

    public boolean setAllianceRanks(int aId, String[] ranks) {
        MapleAlliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.setRankTitle(ranks);
            return true;
        }
        return false;
    }

    public boolean setAllianceNotice(int aId, String notice) {
        MapleAlliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.setNotice(notice);
            return true;
        }
        return false;
    }

    public boolean increaseAllianceCapacity(int aId, int inc) {
        MapleAlliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.increaseCapacity(inc);
            return true;
        }
        return false;
    }
}