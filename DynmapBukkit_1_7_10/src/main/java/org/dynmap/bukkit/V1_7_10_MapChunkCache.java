package org.dynmap.bukkit;

import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.v1_7_R4.ChunkProviderServer;
import net.minecraft.server.v1_7_R4.ChunkRegionLoader;
import net.minecraft.server.v1_7_R4.IChunkLoader;
import net.minecraft.server.v1_7_R4.NBTTagCompound;

import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.block.Biome;
import org.bukkit.craftbukkit.v1_7_R4.CraftWorld;
import org.bukkit.ChunkSnapshot;
import org.dynmap.DynmapChunk;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.bukkit.BaseMapChunkCache;
import org.dynmap.bukkit.SnapshotCache;
import org.dynmap.bukkit.SnapshotCache.SnapshotRec;
import org.dynmap.common.BiomeMap;
import org.dynmap.hdmap.HDBlockModels;
import org.dynmap.renderer.RenderPatchFactory;
import org.dynmap.utils.DynIntHashMap;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.MapIterator;
import org.dynmap.utils.BlockStep;
import org.dynmap.utils.VisibilityLimit;

/**
 * Container for managing chunks - dependent upon using chunk snapshots, since rendering is off server thread
 */
public class V1_7_10_MapChunkCache extends BaseMapChunkCache {
    private boolean doneLoad = false;
    private ChunkProviderServer cps;
    private ChunkRegionLoader loader;
    private static boolean didInit = false;
    private static Field loaderField = null;
    
    public V1_7_10_MapChunkCache() {
        if (!didInit) {
            try {
                loaderField = ChunkProviderServer.class.getField("f");
            } catch (NoSuchFieldException nsfx) {
                Log.info("Error finding chunk loader");
                loaderField = null;
            }
            didInit = true;
        }
    }
    
    private boolean isVisibleChunk(DynmapChunk chunk) {
        boolean vis = true;
        if(visible_limits != null) {
            vis = false;
            for(VisibilityLimit limit : visible_limits) {
                if (limit.doIntersectChunk(chunk.x, chunk.z)) {
                    vis = true;
                    break;
                }
            }
        }
        if(vis && (hidden_limits != null)) {
            for(VisibilityLimit limit : hidden_limits) {
                if (limit.doIntersectChunk(chunk.x, chunk.z)) {
                    vis = false;
                    break;
                }
            }
        }
        return vis;
    }
    private boolean isInCache(DynmapChunk chunk, int idx, boolean vis) {
        SnapshotRec ssr = SnapshotCache.cache.getSnapshot(dw.getName(), chunk.x, chunk.z, blockdata, biome, biomeraw, highesty); 
        if(ssr != null) {
            ChunkSnapshot ss = ssr.ss;
            if(!vis) {
                if(hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN)
                    ss = STONE;
                else if(hidestyle == HiddenChunkStyle.FILL_OCEAN)
                    ss = OCEAN;
                else
                    ss = EMPTY;
            }
            snaparray[idx] = ss;
            snaptile[idx] = ssr.tileData;
            inhabitedTicks[idx] = ssr.inhabitedTicks;            
        }
        return (ssr != null);
    }
    @Override
    public int loadChunks(int max_to_load) {
        if(dw.isLoaded() == false)
            return 0;
        Object queue = helper.getUnloadQueue(w);
        
        int cnt = 0;
        Iterator<DynmapChunk> iterator = chunks.listIterator();

        //boolean isnormral = w.getEnvironment() == Environment.NORMAL;
        // Load the required chunks.
        while (iterator.hasNext()) {
            long startTime = System.nanoTime();
            DynmapChunk chunk = iterator.next();
            int idx = (chunk.x - x_min) + ((chunk.z - z_min) * x_dim);
            
            if (snaparray[idx] != null) {
                continue;
            }
            boolean vis = isVisibleChunk(chunk);
            /* Check if cached chunk snapshot found */
            if (isInCache(chunk, idx, vis)) {
                endChunkLoad(startTime, ChunkStats.CACHED_SNAPSHOT_HIT);
                continue;
            }
            ChunkSnapshot ss = null;
            long inhabited_ticks = 0;
            DynIntHashMap tileData = null;
            // See if chunk already loaded
            boolean wasLoaded = w.isChunkLoaded(chunk.x, chunk.z);
            boolean isunloadpending = false;
            if (queue != null) {
                isunloadpending = helper.isInUnloadQueue(queue, chunk.x, chunk.z);
            }
            // If loaded, and not being unloaded, read live chunk
            if (wasLoaded && (!isunloadpending)) {
                tileData = new DynIntHashMap();

                Chunk c = w.getChunkAt(chunk.x, chunk.z);   /* Get the chunk */
                /* Get inhabited ticks count */
                inhabited_ticks = helper.getInhabitedTicks(c);
                if(!vis) {
                    if(hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN)
                        ss = STONE;
                    else if(hidestyle == HiddenChunkStyle.FILL_OCEAN)
                        ss = OCEAN;
                    else
                        ss = EMPTY;
                }
                else {
                    if(blockdata || highesty) {
                        ss = c.getChunkSnapshot(highesty, biome, biomeraw);
                        /* Get tile entity data */
                        List<Object> vals = new ArrayList<Object>();
                        Map<?,?> tileents = helper.getTileEntitiesForChunk(c);
                        for(Object t : tileents.values()) {
                            int te_x = helper.getTileEntityX(t);
                            int te_y = helper.getTileEntityY(t);
                            int te_z = helper.getTileEntityZ(t);
                            int cx = te_x & 0xF;
                            int cz = te_z & 0xF;
                            int blkid = ss.getBlockTypeId(cx, te_y, cz);
                            int blkdat = ss.getBlockData(cx, te_y, cz);
                            String[] te_fields = HDBlockModels.getTileEntityFieldsNeeded(blkid,  blkdat);
                            if(te_fields != null) {
                                Object nbtcompound = helper.readTileEntityNBT(t);
                                
                                vals.clear();
                                for(String id: te_fields) {
                                    Object val = helper.getFieldValue(nbtcompound, id);
                                    if(val != null) {
                                        vals.add(id);
                                        vals.add(val);
                                    }
                                }
                                if(vals.size() > 0) {
                                    Object[] vlist = vals.toArray(new Object[vals.size()]);
                                    tileData.put(getIndexInChunk(cx,te_y,cz), vlist);
                                }
                            }
                        }
                    }
                    else
                        ss = w.getEmptyChunkSnapshot(chunk.x, chunk.z, biome, biomeraw);
                    if(ss != null) {
                        SnapshotRec ssr = new SnapshotRec();
                        ssr.ss = ss;
                        ssr.inhabitedTicks = inhabited_ticks;
                        ssr.tileData = tileData;
                        SnapshotCache.cache.putSnapshot(dw.getName(), chunk.x, chunk.z, ssr, blockdata, biome, biomeraw, highesty);
                    }
                }
                int chunkIndex = (chunk.x-x_min) + (chunk.z - z_min)*x_dim;
                snaparray[chunkIndex] = ss;
                snaptile[chunkIndex] = tileData;
                inhabitedTicks[chunkIndex] = inhabited_ticks;
                
                endChunkLoad(startTime, ChunkStats.LOADED_CHUNKS);
            }
            else {
                endChunkLoad(startTime, ChunkStats.UNGENERATED_CHUNKS);
            }
            cnt++;
        }
        doneLoad = true;
        
        return cnt;
    }
    /**
     * Test if done loading
     */
    @Override
    public boolean isDoneLoading() {
        return doneLoad;
    }
    @Override
    public int readChunks() {
        if(dw.isLoaded() == false)
            return 0;
        Object queue = helper.getUnloadQueue(w);
        cps = ((CraftWorld) w).getHandle().chunkProviderServer;
        try {
            IChunkLoader cloader = (IChunkLoader) loaderField.get(cps);
            if (cloader instanceof ChunkRegionLoader) {
                loader = (ChunkRegionLoader) cloader;
            }
        } catch (IllegalArgumentException iax) {
        } catch (IllegalAccessException ixx) {
        }
        if (loader == null) {
            Log.warning("Unable to find usable chunk loader");
            return 0;
        }
        int cnt = 0;
        Iterator<DynmapChunk> iterator = chunks.listIterator();

        //boolean isnormral = w.getEnvironment() == Environment.NORMAL;
        // Load the required chunks.
        while (iterator.hasNext()) {
            long startTime = System.nanoTime();
            DynmapChunk chunk = iterator.next();
            int idx = (chunk.x - x_min) + ((chunk.z - z_min) * x_dim);
            if (snaparray[idx] != null) {
                continue;
            }
            boolean vis = isVisibleChunk(chunk);


        }
        // Finish up any chunks that didn't get loaded or read
        isempty = true;
        /* Fill missing chunks with empty dummy chunk */
        for(int i = 0; i < snaparray.length; i++) {
            if(snaparray[i] == null)
                snaparray[i] = EMPTY;
            else if(snaparray[i] != EMPTY)
                isempty = false;
        }
        return 0;
    }
    
    public NBTTagCompound readChunk(int x, int z) {
        try {
            List<?> chunkstoremove = null;
            Set<?> pendingcoords = null;
            LinkedHashMap<?,?> pendingsavesmcpc = null;
            
            if (pendingAnvilChunksMCPC != null) {
                pendingsavesmcpc = (LinkedHashMap<?,?>)pendingAnvilChunksMCPC.get(acl);
            }
            else {
                chunkstoremove = (List<?>)chunksToRemove.get(acl);
                pendingcoords = (Set<?>)pendingAnvilChunksCoordinates.get(acl);
            }
            Object synclock = syncLockObject.get(acl);

            NBTTagCompound rslt = null;
            ChunkCoordIntPair coord = new ChunkCoordIntPair(x, z);

            synchronized (synclock) {
                if (pendingAnvilChunksMCPC != null) {
                    Object rec = pendingsavesmcpc.get(coord);
                    if(rec != null) {
                        if (chunkCoord == null) {
                            Field[] f = rec.getClass().getDeclaredFields();
                            for(Field ff : f) {
                                if((chunkCoord == null) && (ff.getType().equals(ChunkCoordIntPair.class))) {
                                    chunkCoord = ff;
                                }
                                else if((nbtTag == null) && (ff.getType().equals(NBTTagCompound.class))) {
                                    nbtTag = ff;
                                }
                            }
                        }
                        rslt = (NBTTagCompound)nbtTag.get(rec);
                    }
                }
                else {
                    if (pendingcoords.contains(coord)) {
                        for (int i = 0; i < chunkstoremove.size(); i++) {
                            Object o = chunkstoremove.get(i);
                            if (chunkCoord == null) {
                                Field[] f = o.getClass().getDeclaredFields();
                                for(Field ff : f) {
                                    if((chunkCoord == null) && (ff.getType().equals(ChunkCoordIntPair.class))) {
                                        chunkCoord = ff;
                                    }
                                    else if((nbtTag == null) && (ff.getType().equals(NBTTagCompound.class))) {
                                        nbtTag = ff;
                                    }
                                }
                            }
                            ChunkCoordIntPair occ = (ChunkCoordIntPair)chunkCoord.get(o);

                            if (occ.equals(coord)) {
                                rslt = (NBTTagCompound)nbtTag.get(o);
                                break;
                            }
                        }
                    }
                }
            }

            if (rslt == null) {
                DataInputStream str = RegionFileCache.getChunkInputStream(acl.chunkSaveLocation, x, z);

                if (str == null) {
                    return null;
                }
                rslt = CompressedStreamTools.read(str);
            }
            if(rslt != null) 
                rslt = rslt.getCompoundTag("Level");
            return rslt;
        } catch (Exception exc) {
            return null;
        }
    }
    
    private Object getNBTValue(NBTBase v) {
        Object val = null;
        switch(v.getId()) {
            case 1: // Byte
                val = Byte.valueOf(((NBTTagByte)v).func_150290_f());
                break;
            case 2: // Short
                val = Short.valueOf(((NBTTagShort)v).func_150289_e());
                break;
            case 3: // Int
                val = Integer.valueOf(((NBTTagInt)v).func_150287_d());
                break;
            case 4: // Long
                val = Long.valueOf(((NBTTagLong)v).func_150291_c());
                break;
            case 5: // Float
                val = Float.valueOf(((NBTTagFloat)v).func_150288_h());
                break;
            case 6: // Double
                val = Double.valueOf(((NBTTagDouble)v).func_150286_g());
                break;
            case 7: // Byte[]
                val = ((NBTTagByteArray)v).func_150292_c();
                break;
            case 8: // String
                val = ((NBTTagString)v).func_150285_a_();
                break;
            case 9: // List
                NBTTagList tl = (NBTTagList) v;
                ArrayList<Object> vlist = new ArrayList<Object>();
                int type = tl.func_150303_d();
                for (int i = 0; i < tl.tagCount(); i++) {
                    switch (type) {
                        case 5:
                            float fv = tl.func_150308_e(i);
                            vlist.add(fv);
                            break;
                        case 6:
                            double dv = tl.func_150309_d(i);
                            vlist.add(dv);
                            break;
                        case 8:
                            String sv = tl.getStringTagAt(i);
                            vlist.add(sv);
                            break;
                        case 10:
                            NBTTagCompound tc = tl.getCompoundTagAt(i);
                            vlist.add(getNBTValue(tc));
                            break;
                        case 11:
                            int[] ia = tl.func_150306_c(i);
                            vlist.add(ia);
                            break;
                    }
                }
                val = vlist;
                break;
            case 10: // Map
                NBTTagCompound tc = (NBTTagCompound) v;
                HashMap<String, Object> vmap = new HashMap<String, Object>();
                for (Object t : tc.func_150296_c()) {
                    String st = (String) t;
                    NBTBase tg = tc.getTag(st);
                    vmap.put(st, getNBTValue(tg));
                }
                val = vmap;
                break;
            case 11: // Int[]
                val = ((NBTTagIntArray)v).func_150302_c();
                break;
        }
        return val;
    }
    
}
