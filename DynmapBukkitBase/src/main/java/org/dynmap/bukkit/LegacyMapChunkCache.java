package org.dynmap.bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.block.Biome;
import org.bukkit.ChunkSnapshot;
import org.dynmap.DynmapChunk;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
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
public class LegacyMapChunkCache extends BaseMapChunkCache {
    @Override
    public int loadChunks(int max_to_load) {
        if(dw.isLoaded() == false)
            return 0;
        Object queue = helper.getUnloadQueue(w);
        
        int cnt = 0;
        if(iterator == null)
            iterator = chunks.listIterator();

        DynmapCore.setIgnoreChunkLoads(true);
        //boolean isnormral = w.getEnvironment() == Environment.NORMAL;
        // Load the required chunks.
        while((cnt < max_to_load) && iterator.hasNext()) {
            long startTime = System.nanoTime();
            DynmapChunk chunk = iterator.next();
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
            /* Check if cached chunk snapshot found */
            ChunkSnapshot ss = null;
            long inhabited_ticks = 0;
            DynIntHashMap tileData = null;
            SnapshotRec ssr = SnapshotCache.cache.getSnapshot(dw.getName(), chunk.x, chunk.z, blockdata, biome, biomeraw, highesty); 
            if(ssr != null) {
                ss = ssr.ss;
                inhabited_ticks = ssr.inhabitedTicks;
                if(!vis) {
                    if(hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN)
                        ss = STONE;
                    else if(hidestyle == HiddenChunkStyle.FILL_OCEAN)
                        ss = OCEAN;
                    else
                        ss = EMPTY;
                }
                int idx = (chunk.x-x_min) + (chunk.z - z_min)*x_dim;
                snaparray[idx] = ss;
                snaptile[idx] = ssr.tileData;
                inhabitedTicks[idx] = inhabited_ticks;
                
                endChunkLoad(startTime, ChunkStats.CACHED_SNAPSHOT_HIT);
                continue;
            }
            boolean wasLoaded = w.isChunkLoaded(chunk.x, chunk.z);
            boolean didload = false;
            boolean isunloadpending = false;
            if (queue != null) {
                isunloadpending = helper.isInUnloadQueue(queue, chunk.x, chunk.z);
            }
            if (isunloadpending) {  /* Workaround: can't be pending if not loaded */
                wasLoaded = true;
            }
            try {
                didload = w.loadChunk(chunk.x, chunk.z, false);
            } catch (Throwable t) { /* Catch chunk error from Bukkit */
                Log.warning("Bukkit error loading chunk " + chunk.x + "," + chunk.z + " on " + w.getName());
                if(!wasLoaded) {    /* If wasn't loaded, we loaded it if it now is */
                    didload = w.isChunkLoaded(chunk.x, chunk.z);
                }
            }
            /* If it did load, make cache of it */
            if(didload) {
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
                        ssr = new SnapshotRec();
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
                
                /* If wasn't loaded before, we need to do unload */
                if (!wasLoaded) {
                    /* Since we only remember ones we loaded, and we're synchronous, no player has
                     * moved, so it must be safe (also prevent chunk leak, which appears to happen
                     * because isChunkInUse defined "in use" as being within 256 blocks of a player,
                     * while the actual in-use chunk area for a player where the chunks are managed
                     * by the MC base server is 21x21 (or about a 160 block radius).
                     * Also, if we did generate it, need to save it */
                    helper.unloadChunkNoSave(w, c, chunk.x, chunk.z);
                    endChunkLoad(startTime, ChunkStats.UNLOADED_CHUNKS);
                }
                else if (isunloadpending) { /* Else, if loaded and unload is pending */
                    w.unloadChunkRequest(chunk.x, chunk.z); /* Request new unload */
                    endChunkLoad(startTime, ChunkStats.LOADED_CHUNKS);
                }
                else {
                    endChunkLoad(startTime, ChunkStats.LOADED_CHUNKS);
                }
            }
            else {
                endChunkLoad(startTime, ChunkStats.UNGENERATED_CHUNKS);
            }
            cnt++;
        }
        DynmapCore.setIgnoreChunkLoads(false);

        if(iterator.hasNext() == false) {   /* If we're done */
            isempty = true;
            /* Fill missing chunks with empty dummy chunk */
            for(int i = 0; i < snaparray.length; i++) {
                if(snaparray[i] == null)
                    snaparray[i] = EMPTY;
                else if(snaparray[i] != EMPTY)
                    isempty = false;
            }
        }

        return cnt;
    }
    /**
     * Test if done loading
     */
    @Override
    public boolean isDoneLoading() {
        if(dw.isLoaded() == false) {
            isempty = true;
            unloadChunks();
            return true;
        }
        if(iterator != null)
            return !iterator.hasNext();
        return false;
    }
    @Override
    public int readChunks() {
        return 0;
    }
}
