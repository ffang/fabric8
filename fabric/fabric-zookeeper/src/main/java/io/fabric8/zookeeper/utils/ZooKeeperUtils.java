/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.zookeeper.utils;

import io.fabric8.api.FabricException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCacheExtended;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;

import io.fabric8.api.RuntimeProperties;
import io.fabric8.zookeeper.ZkPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

public final class ZooKeeperUtils {

    public static Logger LOG = LoggerFactory.getLogger(ZooKeeperUtils.class);

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String CONTAINERS_NODE = "/fabric/authentication/containers";
    private static final char[] DIGITS_LOWER = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    private ZooKeeperUtils() {
        //Utility Class
    }

    public static void copy(CuratorFramework source, CuratorFramework dest, String path) throws Exception {
        for (String child : source.getChildren().forPath(path)) {
            child = ZKPaths.makePath(path, child);
            Stat stat = source.checkExists().forPath(child);
            if (stat != null && stat.getEphemeralOwner() == 0 && dest.checkExists().forPath(child) == null) {
                byte[] data = source.getData().forPath(child);
                setData(dest, child, data);
                copy(source, dest, child);
            }
        }
    }

    public static void copy(CuratorFramework curator, String from, String to) throws Exception {
        for (String child : curator.getChildren().forPath(from)) {
            String fromChild = from + "/" + child;
            String toChild = to + "/" + child;
            if (curator.checkExists().forPath(toChild) == null) {
                byte[] data = curator.getData().forPath(fromChild);
                setData(curator, toChild, data);
                copy(curator, fromChild, toChild);
            }
        }
    }

    public static void add(CuratorFramework curator, String path, String value) throws Exception {
        if (curator.checkExists().forPath(path) == null) {
            curator.setData().forPath(path, value != null ? value.getBytes(UTF_8) : null);
        } else {
            String data = getStringData(curator, path);
            if (data == null) {
                data = "";
            }
            if (data.length() > 0) {
                data += " ";
            }
            data += value;
            curator.setData().forPath(path, data.getBytes(UTF_8));
        }
    }

    public static void remove(CuratorFramework curator, String path, String value) throws Exception {
        if (curator.checkExists().forPath(path) != null) {
            List<String> parts = new LinkedList<String>();
            String data = getStringData(curator, path);
            if (data != null) {
                parts = new ArrayList<String>(Arrays.asList(data.trim().split(" +")));
            }
            boolean changed = false;
            StringBuilder sb = new StringBuilder();
            for (Iterator<String> it = parts.iterator(); it.hasNext(); ) {
                String v = it.next();
                if (v.matches(value)) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) {
                sb.delete(0, sb.length());
                for (String part : parts) {
                    if (sb.length() > 0) {
                        sb.append(" ");
                    }
                    sb.append(part);
                }
                setData(curator, path, sb.toString());
            }
        }
    }


    public static List<String> getChildren(CuratorFramework curator, String path) throws Exception {
        return curator.getChildren().forPath(path);
    }

    /**
     * Returns an empty list if the given path doesn't exist in curator
     */
    public static List<String> getChildrenSafe(CuratorFramework curator, String path) throws Exception {
        boolean pathExists = curator.checkExists().forPath(path) != null;
        return pathExists ? curator.getChildren().forPath(path) : Collections.<String>emptyList();
    }

    public static List<String> getChildren(TreeCacheExtended cache, String path) throws Exception {
        return cache.getChildrenNames(path);
    }

    public static List<String> getAllChildren(CuratorFramework curator, String path) throws Exception {
        List<String> children = getChildren(curator, path);
        List<String> allChildren = new ArrayList<String>();
        for (String child : children) {
            String fullPath = ZKPaths.makePath(path, child);
            allChildren.add(fullPath);
            allChildren.addAll(getAllChildren(curator, fullPath));
        }
        return allChildren;
    }

    public static List<String> getAllChildren(TreeCacheExtended cache, String path) throws Exception {
        List<String> children = getChildren(cache, path);
        List<String> allChildren = new ArrayList<String>();
        for (String child : children) {
            String fullPath = ZKPaths.makePath(path, child);
            allChildren.add(fullPath);
            allChildren.addAll(getAllChildren(cache, fullPath));
        }
        return allChildren;
    }


    public static byte[] getByteData(TreeCacheExtended cache, String path) throws Exception {
        ChildData cacheData = cache.getCurrentData(path);
        if (cacheData != null) {
            return cacheData.getData();
        } else {
            return null;
        }
    }

    public static String getStringData(TreeCacheExtended cache, String path) throws Exception {
        byte[] data = getByteData(cache, path);
        if (data == null) {
            return null;
        } else {
            return new String(data, UTF_8);
        }
    }

    public static String getStringData(CuratorFramework curator, String path) throws Exception {
        return getStringData(curator, path, null);
    }

    public static String getStringData(CuratorFramework curator, String path, Watcher watcher) throws Exception {
        byte[] bytes = watcher != null ? curator.getData().usingWatcher(watcher).forPath(path) : curator.getData().forPath(path);
        if (bytes == null) {
            return null;
        } else {
            return new String(bytes, UTF_8);
        }
    }

    public static String getStringDataSafe(CuratorFramework curator, String path) throws Exception {
        byte[] bytes = getData(curator, path);
        if (bytes == null) {
            return null;
        } else {
            return new String(bytes, UTF_8);
        }
    }

    /**
     * Returns the data for the given path or null if it doesn not exist
     */
    public static byte[] getData(CuratorFramework curator, String path) throws Exception {
        if (curator.checkExists().forPath(path) != null) {
            return curator.getData().forPath(path);
        }
        return null;
    }


    public static void setData(CuratorFramework curator, String path, String value) throws Exception {
        setData(curator, path, value != null ? value.getBytes(UTF_8) : null);
    }

    public static void setData(CuratorFramework curator, String path, byte[] value) throws Exception {
        if (curator.checkExists().forPath(path) == null) {
            curator.create().creatingParentsIfNeeded().forPath(path, value != null ? value : null);
        }
        curator.setData().forPath(path, value != null ? value : null);
    }

    public static void setData(CuratorFramework curator, String path, String value, CreateMode createMode) throws Exception {
        setData(curator, path, value != null ? value.getBytes(UTF_8) : null, createMode);
    }

    public static void setData(CuratorFramework curator, String path, byte[] value, CreateMode createMode) throws Exception {
        if (curator.checkExists().forPath(path) == null) {
            curator.create().creatingParentsIfNeeded().withMode(createMode).forPath(path, value != null ? value : null);
        }
        curator.setData().forPath(path, value != null ? value : null);
    }

    public static void create(CuratorFramework curator, String path) throws Exception {
        create(curator, path, CreateMode.PERSISTENT);
    }

    public static String create(CuratorFramework curator, String path, CreateMode createMode) throws Exception {
        return create(curator, path, (byte[]) null, createMode);
    }

    public static String create(CuratorFramework curator, String path, String data, CreateMode createMode) throws Exception {
        return create(curator, path, data != null ? data.getBytes(UTF_8) : null, createMode);
    }

    public static String create(CuratorFramework curator, String path, byte[] data, CreateMode createMode) throws Exception {
        return curator.create().creatingParentsIfNeeded().withMode(createMode).forPath(path, data);
    }

    public static void createDefault(CuratorFramework curator, String path, String value) throws Exception {
        if (curator.checkExists().forPath(path) == null) {
            curator.create().creatingParentsIfNeeded().forPath(path, value != null ? value.getBytes(UTF_8) : null);
        }
    }

    public static void deleteSafe(CuratorFramework curator, String path) throws Exception {
        if (curator.checkExists().forPath(path) != null) {
            for (String child : curator.getChildren().forPath(path)) {
                deleteSafe(curator, path + "/" + child);
            }
            try {
                curator.delete().forPath(path);
            } catch (KeeperException.NotEmptyException ex) {
                deleteSafe(curator, path);
            }
        }
    }

    /**
     * Deletes a path and (when there are no other children) also parent path - up to given {@code parent}
     * @param curator
     * @param path
     * @param parent
     * @throws Exception
     */
    public static void deleteSafeUpTo(CuratorFramework curator, String path, String ... parent) throws Exception {
        if (curator.checkExists().forPath(path) != null) {
            for (String child : curator.getChildren().forPath(path)) {
                deleteSafe(curator, path + "/" + child);
            }
            try {
                curator.delete().forPath(path);
                String parentPath = getParent(path);
                while (true) {
                    boolean end = false;
                    for (String p : parent) {
                        if (parentPath.equals(p)) {
                            end = true;
                        }
                    }
                    if (end) {
                        break;
                    }
                    List<String> children = curator.getChildren().forPath(parentPath);
                    if (children != null && children.size() > 0) {
                        break;
                    }
                    curator.delete().forPath(parentPath);
                    parentPath = getParent(parentPath);
                }
                //curator.getChildren().forPath(path)
            } catch (KeeperException.NotEmptyException ex) {
                deleteSafe(curator, path);
            }
        }
    }

    public static String getParent(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Path is not valid: " + path);
        }
        String[] segments = path.split("/");
        StringBuilder sb = new StringBuilder();
        if (segments.length <= 2) {
            return "/";
        }
        for (int i = 1; i < segments.length - 1; i++) {
            sb.append("/").append(segments[i]);
        }
        return sb.toString();
    }

    public static void delete(CuratorFramework curator, String path) throws Exception {
        curator.delete().forPath(path);
    }

    public static Stat exists(CuratorFramework curator, String path) throws Exception {
        return curator.checkExists().forPath(path);
    }

    public static Properties getProperties(CuratorFramework curator, String path, Watcher watcher) throws Exception {
        String value = getStringData(curator, path, watcher);
        Properties properties = new Properties();
        if (value != null) {
            try {
                properties.load(new StringReader(value));
            } catch (IOException ignore) {
            }
        }
        return properties;
    }

    public static Map<String, String> getPropertiesAsMap(CuratorFramework curator, String path) throws Exception {
        Properties properties = getProperties(curator, path);
        Map<String, String> map = new HashMap<String, String>();
        for (String key : properties.stringPropertyNames()) {
            map.put(key, properties.getProperty(key));
        }
        return map;
    }

    public static Map<String, String> getPropertiesAsMap(TreeCacheExtended cache, String path) throws Exception {
        Properties properties = getProperties(cache, path);
        Map<String, String> map = new HashMap<String, String>();
        for (String key : properties.stringPropertyNames()) {
            map.put(key, properties.getProperty(key));
        }
        return map;
    }

    public static Properties getProperties(CuratorFramework curator, String path) throws Exception {
        String value = getStringDataSafe(curator, path);
        Properties properties = new Properties();
        if (value != null) {
            try {
                properties.load(new StringReader(value));
            } catch (IOException ignore) {
            }
        }
        return properties;
    }

    public static Properties getProperties(TreeCacheExtended cace, String path) throws Exception {
        String value = getStringData(cace, path);
        Properties properties = new Properties();
        if (value != null) {
            try {
                properties.load(new StringReader(value));
            } catch (IOException ignore) {
            }
        }
        return properties;
    }

    public static void setPropertiesAsMap(CuratorFramework curator, String path, Map<String, String> map) throws Exception {
        Properties properties = new Properties();
        for (String key : map.keySet()) {
            properties.put(key, map.get(key));
        }
        setProperties(curator, path, properties);
    }

    public static void setProperties(CuratorFramework curator, String path, Properties properties) throws Exception {
        try {
            org.apache.felix.utils.properties.Properties p = new org.apache.felix.utils.properties.Properties();
            if(curator.checkExists().forPath(path) == null){
                create(curator, path);
            }
            String org = getStringData(curator, path);
            if (org != null) {
                p.load(new StringReader(org));
            }
            List<String> keys = new ArrayList<String>();
            for (String key : properties.stringPropertyNames()) {
                p.put(key, properties.getProperty(key));
                keys.add(key);
            }
            List<String> deleted = new ArrayList<String>(p.keySet());
            deleted.removeAll(keys);
            for (String key : deleted) {
                p.remove(key);
            }
            StringWriter writer = new StringWriter();
            p.save(writer);
            setData(curator, path, writer.toString());
        } catch (IOException e) {
            // ignore
        }
    }

    public static String getSubstitutedPath(final CuratorFramework curator, String path) throws Exception {
        String normalized = path != null && path.contains("#") ? path.substring(0, path.lastIndexOf('#')) : path;
        if (normalized != null && exists(curator, normalized) != null) {
            byte[] data = ZkPath.loadURL(curator, path);
            if (data != null && data.length > 0) {
                String str = new String(ZkPath.loadURL(curator, path), "UTF-8");
                return getSubstitutedData(curator, str);
            }
        }
        return null;
    }

    public static String getSubstitutedData(final CuratorFramework curator, String data) throws URISyntaxException {
        if (data == null) {
            return null;
        }
        Map<String, String> props = new HashMap<String, String>();
        props.put("data", data);

        InterpolationHelper.performSubstitution(props, new InterpolationHelper.SubstitutionCallback() {
            @Override
            public String getValue(String key) {
                if (key.startsWith("zk:")) {
                    try {
                        return new String(ZkPath.loadURL(curator, key), "UTF-8");
                    } catch (Exception e) {
                        //ignore and just return null.
                    }
                }
                return null;
            }
        });
        return props.get("data");
    }

    /**
     * Generate a random String that can be used as a Zookeeper password.
     */
    public static String generatePassword() {
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            long l = Math.round(Math.floor(Math.random() * (26 * 2 + 10)));
            if (l < 10) {
                password.append((char) ('0' + l));
            } else if (l < 36) {
                password.append((char) ('A' + l - 10));
            } else {
                password.append((char) ('a' + l - 36));
            }
        }
        return password.toString();
    }

    /**
     * Returns the last modified time of the znode taking children into consideration.
     */
    public static long lastModified(CuratorFramework curator, String path) throws Exception {
        long lastModified = 0;
        List<String> children = getChildren(curator, path);
        if (children.isEmpty()) {
            return exists(curator, path).getMtime();
        } else {
            for (String child : children) {
                lastModified = Math.max(lastModified(curator, path + "/" + child), lastModified);
            }
        }
        return lastModified;
    }

    public static String getContainerLogin(RuntimeProperties sysprops) {
        String container = sysprops.getRuntimeIdentity();
        return "container#" + container;
    }

    public static boolean isContainerLogin(String login) {
        return login.startsWith("container#");
    }

    public static Properties getContainerTokens(CuratorFramework curator) throws Exception {
        Properties props = new Properties();
        if (exists(curator, CONTAINERS_NODE) != null) {
            for (String key : getChildren(curator, CONTAINERS_NODE)) {
                props.setProperty("container#" + key, getStringData(curator, CONTAINERS_NODE + "/" + key));
            }
        }
        return props;
    }

    private static volatile long lastTokenGenerationTime = 0;

    public static String generateContainerToken(RuntimeProperties sysprops, CuratorFramework curator) {
        String container = sysprops.getRuntimeIdentity();
        long time = System.currentTimeMillis();
        String password = null;
        try {
            if (time - lastTokenGenerationTime < 60 * 1000) {
                try {
                    password = getStringData(curator, CONTAINERS_NODE + "/" + container);
                } catch (KeeperException.NoNodeException ex) {
                    //Node hasn't been created yet. It's safe to ignore.
                }
            }
            if (password == null) {
                password = generatePassword();
                setData(curator, CONTAINERS_NODE + "/" + container, password);
                lastTokenGenerationTime = time;
            }
        } catch (KeeperException.NotReadOnlyException e) {
            throw new FabricException("ZooKeeper server is partitioned. Currently working in read-only mode!");
        } catch (RuntimeException rte) {
            throw rte;
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot generate container token", ex);
        }
        return password;
    }


    /**
     * Digests common configuration properties and one peer-specific property into given peer password
     * @param props
     * @param serverId
     * @return
     */
    public static String derivePeerPassword(Properties props, int serverId) throws GeneralSecurityException {
        Map<String, String> c14n = new TreeMap<>();
        for (String property : props.stringPropertyNames()) {
            if (property.startsWith("clientPort")
                    || property.equals("component.id")
                    || property.equals("server.id") // this will be handled specially
                    || property.equals("dataDir") // this is also container-specific
                    || property.equals("service.pid")
                    || property.equals("felix.fileinstall.filename")) {
                continue;
            }
            String v = props.getProperty(property);
            if (v != null) {
                c14n.put(property, v.trim());
            }
        }
        MessageDigest sha = MessageDigest.getInstance("SHA");
        LOG.info(String.format("== Deriving password for peer %d", serverId));
        for (Map.Entry<String, String> entry : c14n.entrySet()) {
            sha.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            sha.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
        }

        // and now, given peer's id
        sha.update("server.id".getBytes(StandardCharsets.UTF_8));
        sha.update(Integer.toString(serverId).getBytes(StandardCharsets.UTF_8));
        byte[] digest = sha.digest();
        return new String(encodeHex(digest, DIGITS_LOWER));
    }

    /**
     * See org.apache.commons.codec.binary.Hex#encodeHex(byte[], char[])
     * @param data
     * @param toDigits
     * @return
     */
    protected static char[] encodeHex(final byte[] data, final char[] toDigits) {
        final int l = data.length;
        final char[] out = new char[l << 1];
        // two characters form the hex value.
        for (int i = 0, j = 0; i < l; i++) {
            out[j++] = toDigits[(0xF0 & data[i]) >>> 4];
            out[j++] = toDigits[0x0F & data[i]];
        }
        return out;
    }

}
