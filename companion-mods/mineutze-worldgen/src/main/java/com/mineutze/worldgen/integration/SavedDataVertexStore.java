package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.regionsolve.VertexStoreBacking;
import com.mineutze.worldgen.regionsolve.topology.HexGrid;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.VertexKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge {@code SavedData}-backed {@link VertexStoreBacking} — same storage-only
 * sharding discipline as {@link SavedDataFaceStore}, one level down (a mesh vertex
 * instead of a mesh face). Must actually persist real data (not just structurally
 * exist) — see {@link com.mineutze.worldgen.regionsolve.DecidedVertexStore}'s own doc for
 * why a vertex position can't be safely recomputed from scratch after a restart and
 * trusted to match what an earlier session already rasterized real, saved terrain
 * against.
 *
 * <p>Sharded by the {@link VertexKey}'s own first (canonically-sorted) owning face's
 * {@link HexGrid.AxialPos} — arbitrary but deterministic, since {@link VertexKey}'s own
 * constructor already canonically sorts its 3 faces regardless of caller-supplied order.
 */
public final class SavedDataVertexStore implements VertexStoreBacking {

    private static final int SHARD_SIZE = 64;

    private final ServerLevel level;

    public SavedDataVertexStore(ServerLevel level) {
        this.level = level;
    }

    @Override
    public Optional<Point2D> load(VertexKey key) {
        return shardFor(key).get(key);
    }

    @Override
    public void save(VertexKey key, Point2D position) {
        shardFor(key).put(key, position);
    }

    private VertexShardData shardFor(VertexKey key) {
        ShardKey shard = shardKeyOf(key);
        SavedData.Factory<VertexShardData> factory = new SavedData.Factory<>(VertexShardData::empty, VertexShardData::load);
        return level.getDataStorage().computeIfAbsent(factory, saveName(shard));
    }

    private static ShardKey shardKeyOf(VertexKey key) {
        HexGrid.AxialPos anchor = key.a();
        return new ShardKey(Math.floorDiv(anchor.q(), SHARD_SIZE), Math.floorDiv(anchor.r(), SHARD_SIZE));
    }

    private static String saveName(ShardKey shard) {
        return "mineutze_worldgen_vertexshard_" + shard.shardQ() + "_" + shard.shardR();
    }

    private record ShardKey(int shardQ, int shardR) {
    }

    /** One shard's worth of already-decided vertex positions, mutable across many {@link #put} calls over this world's lifetime. */
    private static final class VertexShardData extends SavedData {

        private static final int SCHEMA_VERSION = 1;
        private static final String SCHEMA_VERSION_KEY = "schemaVersion";
        private static final String VERTICES_KEY = "vertices";
        private static final String AQ_KEY = "aq";
        private static final String AR_KEY = "ar";
        private static final String BQ_KEY = "bq";
        private static final String BR_KEY = "br";
        private static final String CQ_KEY = "cq";
        private static final String CR_KEY = "cr";
        private static final String X_KEY = "x";
        private static final String Z_KEY = "z";

        private final Map<VertexKey, Point2D> vertices = new ConcurrentHashMap<>();

        static VertexShardData empty() {
            return new VertexShardData();
        }

        Optional<Point2D> get(VertexKey key) {
            return Optional.ofNullable(vertices.get(key));
        }

        void put(VertexKey key, Point2D position) {
            vertices.put(key, position);
            setDirty();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            tag.putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION);
            ListTag list = new ListTag();
            vertices.forEach((key, position) -> {
                CompoundTag vertexTag = new CompoundTag();
                vertexTag.putInt(AQ_KEY, key.a().q());
                vertexTag.putInt(AR_KEY, key.a().r());
                vertexTag.putInt(BQ_KEY, key.b().q());
                vertexTag.putInt(BR_KEY, key.b().r());
                vertexTag.putInt(CQ_KEY, key.c().q());
                vertexTag.putInt(CR_KEY, key.c().r());
                vertexTag.putDouble(X_KEY, position.x());
                vertexTag.putDouble(Z_KEY, position.z());
                list.add(vertexTag);
            });
            tag.put(VERTICES_KEY, list);
            return tag;
        }

        static VertexShardData load(CompoundTag tag, HolderLookup.Provider provider) {
            int schemaVersion = tag.getInt(SCHEMA_VERSION_KEY);
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalStateException(
                    "SavedDataVertexStore: unknown schema version " + schemaVersion
                        + " (expected " + SCHEMA_VERSION + ") - a real migration belongs here once this ever changes"
                );
            }
            VertexShardData data = new VertexShardData();
            ListTag list = tag.getList(VERTICES_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag vertexTag = list.getCompound(i);
                VertexKey key = new VertexKey(
                    new HexGrid.AxialPos(vertexTag.getInt(AQ_KEY), vertexTag.getInt(AR_KEY)),
                    new HexGrid.AxialPos(vertexTag.getInt(BQ_KEY), vertexTag.getInt(BR_KEY)),
                    new HexGrid.AxialPos(vertexTag.getInt(CQ_KEY), vertexTag.getInt(CR_KEY))
                );
                Point2D position = new Point2D(vertexTag.getDouble(X_KEY), vertexTag.getDouble(Z_KEY));
                data.vertices.put(key, position);
            }
            return data;
        }
    }
}
