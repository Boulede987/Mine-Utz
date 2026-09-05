package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.regionsolve.DecidedFaceStore;
import com.mineutze.worldgen.regionsolve.FaceStoreBacking;
import com.mineutze.worldgen.regionsolve.topology.HexGrid;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge {@code SavedData}-backed {@link FaceStoreBacking} — one file per fixed-size
 * SHARD of {@link HexGrid.AxialPos}, never one file per face (that would be an absurd
 * number of tiny files at real exploration scale). The shard boundary is a storage-only
 * bucket, picked purely to keep file count/size reasonable — it is NEVER consulted for
 * legality or ownership (see this whole redesign's own plan doc for why re-introducing
 * that exact mistake at the storage layer would defeat the entire point of moving off
 * {@code RegionPos}). A face's real identity is still its own {@link HexGrid.AxialPos},
 * looked up directly regardless of which shard happens to hold it.
 *
 * <p>Unlike the older {@code SavedDataRegionPersistenceStore} (one region solved, and
 * therefore saved, exactly once ever), a shard here accumulates MANY faces over time as
 * the player explores — so each shard's own {@code SavedData} is a genuinely mutable,
 * reusable object (backed by NeoForge's own {@code DimensionDataStorage} in-memory
 * instance cache), not a fresh one constructed per save.
 */
public final class SavedDataFaceStore implements FaceStoreBacking {

    // Arbitrary storage bucket size, not tied to any gameplay/legality distance - see this
    // class's own doc. Coarse enough to keep the shard file count reasonable at real
    // exploration scale without any single shard file growing unreasonably large.
    private static final int SHARD_SIZE = 64;

    private final ServerLevel level;

    public SavedDataFaceStore(ServerLevel level) {
        this.level = level;
    }

    @Override
    public Optional<DecidedFaceStore.DecidedFace> load(HexGrid.AxialPos axialPos) {
        return shardFor(axialPos).get(axialPos);
    }

    @Override
    public void save(HexGrid.AxialPos axialPos, DecidedFaceStore.DecidedFace face) {
        shardFor(axialPos).put(axialPos, face);
    }

    private FaceShardData shardFor(HexGrid.AxialPos axialPos) {
        ShardKey shard = shardKeyOf(axialPos);
        SavedData.Factory<FaceShardData> factory = new SavedData.Factory<>(FaceShardData::empty, FaceShardData::load);
        return level.getDataStorage().computeIfAbsent(factory, saveName(shard));
    }

    private static ShardKey shardKeyOf(HexGrid.AxialPos axialPos) {
        return new ShardKey(Math.floorDiv(axialPos.q(), SHARD_SIZE), Math.floorDiv(axialPos.r(), SHARD_SIZE));
    }

    private static String saveName(ShardKey shard) {
        return "mineutze_worldgen_faceshard_" + shard.shardQ() + "_" + shard.shardR();
    }

    private record ShardKey(int shardQ, int shardR) {
    }

    /** One shard's worth of already-decided faces, mutable across many {@link #put} calls over this world's lifetime. */
    private static final class FaceShardData extends SavedData {

        // Bumped only if this NBT shape ever changes - see SavedDataRegionPersistenceStore's own doc for why a real migration path belongs here once that happens.
        private static final int SCHEMA_VERSION = 1;
        private static final String SCHEMA_VERSION_KEY = "schemaVersion";
        private static final String FACES_KEY = "faces";
        private static final String Q_KEY = "q";
        private static final String R_KEY = "r";
        private static final String BIOME_KEY = "biome";
        private static final String WIDTH_KEY = "width";

        private final Map<HexGrid.AxialPos, DecidedFaceStore.DecidedFace> faces = new ConcurrentHashMap<>();

        static FaceShardData empty() {
            return new FaceShardData();
        }

        Optional<DecidedFaceStore.DecidedFace> get(HexGrid.AxialPos axialPos) {
            return Optional.ofNullable(faces.get(axialPos));
        }

        void put(HexGrid.AxialPos axialPos, DecidedFaceStore.DecidedFace face) {
            faces.put(axialPos, face);
            setDirty();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            tag.putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION);
            ListTag list = new ListTag();
            faces.forEach((axialPos, face) -> {
                CompoundTag faceTag = new CompoundTag();
                faceTag.putInt(Q_KEY, axialPos.q());
                faceTag.putInt(R_KEY, axialPos.r());
                faceTag.putString(BIOME_KEY, BiomeIdAdapter.toResourceLocation(face.biomeId()).toString());
                faceTag.putInt(WIDTH_KEY, face.widthBlocks());
                list.add(faceTag);
            });
            tag.put(FACES_KEY, list);
            return tag;
        }

        static FaceShardData load(CompoundTag tag, HolderLookup.Provider provider) {
            int schemaVersion = tag.getInt(SCHEMA_VERSION_KEY);
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalStateException(
                    "SavedDataFaceStore: unknown schema version " + schemaVersion
                        + " (expected " + SCHEMA_VERSION + ") - a real migration belongs here once this ever changes"
                );
            }
            FaceShardData data = new FaceShardData();
            ListTag list = tag.getList(FACES_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag faceTag = list.getCompound(i);
                HexGrid.AxialPos axialPos = new HexGrid.AxialPos(faceTag.getInt(Q_KEY), faceTag.getInt(R_KEY));
                var biome = BiomeIdAdapter.toBiomeId(ResourceLocation.parse(faceTag.getString(BIOME_KEY)));
                data.faces.put(axialPos, new DecidedFaceStore.DecidedFace(biome, faceTag.getInt(WIDTH_KEY)));
            }
            return data;
        }
    }
}
