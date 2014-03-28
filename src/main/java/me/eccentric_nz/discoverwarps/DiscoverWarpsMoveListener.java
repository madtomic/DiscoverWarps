package me.eccentric_nz.discoverwarps;

import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class DiscoverWarpsMoveListener implements Listener {

    DiscoverWarps plugin;
    DiscoverWarpsDatabase service = DiscoverWarpsDatabase.getInstance();
    HashMap<String, List<String>> regionPlayers = new HashMap<String, List<String>>();
    WorldGuardPlugin wg;

    public DiscoverWarpsMoveListener(DiscoverWarps plugin) {
        this.plugin = plugin;
        this.wg = (WorldGuardPlugin) plugin.pm.getPlugin("WorldGuard");
        setupRegionPlayers();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        String name = p.getName();
        if (p.hasPermission("discoverwarps.use")) {
            Location l = event.getTo();
            Location loc = p.getLocation(); // Grab Location

            /**
             * Copyright (c) 2011, The Multiverse Team All rights reserved.
             * Check the Player has actually moved a block to prevent unneeded
             * calculations... This is to prevent huge performance drops on high
             * player count servers.
             */
            DiscoverWarpsSession dws = plugin.getDiscoverWarpsSession(p);
            dws.setStaleLocation(loc);

            // If the location is stale, ie: the player isn't actually moving xyz coords, they're looking around
            if (dws.isStaleLocation()) {
                return;
            }
            RegionManager rm = wg.getRegionManager(l.getWorld());
            ApplicableRegionSet ars = rm.getApplicableRegions(l);
            if (ars.size() > 0) {
                // get the region
                String region = getRegion(ars);
                String w = l.getWorld().getName();
                boolean discovered = false;
                boolean firstplate = true;
                Statement statement = null;
                ResultSet rsPlate = null;
                ResultSet rsPlayer = null;
                try {
                    Connection connection = service.getConnection();
                    statement = connection.createStatement();
                    // get their current gamemode inventory from database
                    String getQuery = "SELECT * FROM discoverwarps WHERE world = '" + w + "' AND region = '" + region + "'";
                    rsPlate = statement.executeQuery(getQuery);
                    if (rsPlate.next() && (!regionPlayers.containsKey(name) || !regionPlayers.get(name).contains(region))) {
                        // add region to the player's list
                        List<String> theList;
                        if (regionPlayers.containsKey(name)) {
                            theList = regionPlayers.get(name);
                        } else {
                            theList = new ArrayList<String>();
                        }
                        theList.add(region);
                        regionPlayers.put(name, theList);
                        // found a discoverplate
                        boolean enabled = rsPlate.getBoolean("enabled");
                        if (enabled) {
                            String id = rsPlate.getString("id");
                            String warp = rsPlate.getString("name");
                            String queryDiscover = "";
                            // check whether they have visited this plate before
                            String queryPlayer = "SELECT * FROM players WHERE player = '" + name + "'";
                            rsPlayer = statement.executeQuery(queryPlayer);
                            if (rsPlayer.next()) {
                                firstplate = false;
                                String data = rsPlayer.getString("visited");
                                String[] visited = data.split(",");
                                if (Arrays.asList(visited).contains(id)) {
                                    discovered = true;
                                }
                                if (discovered == false) {
                                    queryDiscover = "UPDATE players SET visited = '" + data + "," + id + "', regions = '" + rsPlayer.getString("regions") + "," + region + "' WHERE player = '" + name + "'";
                                }
                            }
                            if (discovered == false && firstplate == true) {
                                queryDiscover = "INSERT INTO players (player, visited, regions) VALUES ('" + name + "','" + id + "','" + region + "')";
                            }
                            statement.executeUpdate(queryDiscover);
                            if (plugin.getConfig().getBoolean("xp_on_discover") && discovered == false) {
//                                Location loc = p.getLocation();
                                loc.setX(loc.getBlockX() + 1);
                                World world = loc.getWorld();
                                ((ExperienceOrb) world.spawn(loc, ExperienceOrb.class)).setExperience(plugin.getConfig().getInt("xp_to_give"));
                            }
                            if (discovered == false) {
                                p.sendMessage(ChatColor.GOLD + "[" + plugin.getConfig().getString("localisation.plugin_name") + "] " + ChatColor.RESET + String.format(plugin.getConfig().getString("localisation.discovered"), warp));
                            }
                            rsPlayer.close();
                            rsPlate.close();
                            statement.close();
                        }
                    }
                } catch (SQLException e) {
                    plugin.debug("Could not update player's visited data, " + e);
                } finally {
                    if (rsPlayer != null) {
                        try {
                            rsPlayer.close();
                        } catch (SQLException e) {
                        }
                    }
                    if (rsPlate != null) {
                        try {
                            rsPlate.close();
                        } catch (SQLException e) {
                        }
                    }
                    if (statement != null) {
                        try {

                            statement.close();
                        } catch (SQLException e) {
                        }
                    }
                }
            }
        }
    }

    /**
     * Gets the innermost region of a set of WorldGuard regions.
     *
     * @param ars The WorldGuard ApplicableRegionSet to search
     * @return the region name
     */
    public static String getRegion(ApplicableRegionSet ars) {
        LinkedList< String> parentNames = new LinkedList< String>();
        LinkedList< String> regions = new LinkedList< String>();
        for (ProtectedRegion pr : ars) {
            String id = pr.getId();
            regions.add(id);
            ProtectedRegion parent = pr.getParent();
            while (parent != null) {
                parentNames.add(parent.getId());
                parent = parent.getParent();
            }
        }
        for (String name : parentNames) {
            regions.remove(name);
        }
        return regions.getFirst();
    }

    private void setupRegionPlayers() {
        // get regions players have visited
        ResultSet rs = null;
        Statement statement = null;
        try {
            Connection connection = service.getConnection();
            statement = connection.createStatement();
            String query = "SELECT player, regions FROM players";
            rs = statement.executeQuery(query);
            if (rs != null && rs.isBeforeFirst()) {
                while (rs.next()) {
                    String r = rs.getString("regions");
                    if (!rs.wasNull()) {
                        List<String> regions = Arrays.asList(r.split(","));
                        regionPlayers.put(rs.getString("player"), regions);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.debug("Could not get region lists!");
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ex) {
                }
            }
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ex) {
                }
            }
        }
    }
}