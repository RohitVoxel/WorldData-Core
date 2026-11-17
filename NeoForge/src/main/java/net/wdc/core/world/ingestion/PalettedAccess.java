package net.wdc.core.world.ingestion;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;

public final class PalettedAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Ingest");
    private static final MethodHandle DATA;
    private static final MethodHandle STORAGE;
    private static final MethodHandle PALETTE;
    private static final ThreadLocal<int[]> LUT = ThreadLocal.withInitial(() -> new int[256]);
    private static final boolean CAPABLE;
    private static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("worlddata.rawPalette"));

    static {
        MethodHandle data = null;
        MethodHandle storage = null;
        MethodHandle palette = null;
        try {
            Field dataField = findDataField();
            if (dataField != null) {
                Class<?> dataType = dataField.getType();
                MethodHandles.Lookup containerLookup = MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup());
                MethodHandles.Lookup dataLookup = MethodHandles.privateLookupIn(dataType, MethodHandles.lookup());
                data = containerLookup.unreflectGetter(dataField).asType(MethodType.methodType(Object.class, Object.class));
                for (RecordComponent component : dataType.getRecordComponents()) {
                    if (component.getType() == BitStorage.class) {
                        storage = dataLookup.unreflect(component.getAccessor()).asType(MethodType.methodType(BitStorage.class, Object.class));
                    } else if (component.getType() == Palette.class) {
                        palette = dataLookup.unreflect(component.getAccessor()).asType(MethodType.methodType(Palette.class, Object.class));
                    }
                }
            }
        } catch (Throwable failure) {
            data = null;
            storage = null;
            palette = null;
            LOGGER.warn("Raw palette access unavailable, using per-block reads: {}", failure.toString());
        }
        DATA = data;
        STORAGE = storage;
        PALETTE = palette;
        CAPABLE = data != null && storage != null && palette != null;
        if (!CAPABLE) LOGGER.info("Raw palette access is not active; chunk ingestion uses the compatible per-block path");
    }

    private PalettedAccess() {}

    private static Field findDataField() {
        for (Field field : PalettedContainer.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            if (!type.isRecord()) continue;
            boolean hasStorage = false;
            boolean hasPalette = false;
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.getType() == BitStorage.class) hasStorage = true;
                if (component.getType() == Palette.class) hasPalette = true;
            }
            if (hasStorage && hasPalette) return field;
        }
        return null;
    }

    public static boolean isAvailable() {
        return CAPABLE && enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    @SuppressWarnings("unchecked")
    public static boolean unpack(LevelChunkSection section, BlockStateInterner interner, SectionUnpacker unpacker, SectionTarget target) {
        if (!CAPABLE || !enabled) return false;
        try {
            PalettedContainer<BlockState> container = section.getStates();
            Object data = (Object) DATA.invokeExact((Object) container);
            BitStorage storage = (BitStorage) STORAGE.invokeExact(data);
            Palette<BlockState> palette = (Palette<BlockState>) (Palette<?>) PALETTE.invokeExact(data);
            int bits = storage.getBits();
            if (bits == 0) {
                BlockState only = palette.valueFor(0);
                if (only == null) return false;
                target.setUniform(interner.tokenOf(only));
                return true;
            }
            long[] raw = storage.getRaw();
            if (bits <= 8) {
                int size = palette.getSize();
                int lutSize = 1 << bits;
                if (size > lutSize) return false;
                int[] lut = LUT.get();
                for (int i = 0; i < lutSize; i++) lut[i] = 0;
                for (int i = 0; i < size; i++) {
                    BlockState state = palette.valueFor(i);
                    lut[i] = state == null ? 0 : interner.tokenOf(state);
                }
                if (SectionUnpacker.unpackPalette(raw, bits, lut, lutSize, target)) return true;
                target.discard();
                return false;
            }
            boolean ok = unpacker.unpackLazy(raw, bits, id -> {
                BlockState state = palette.valueFor(id);
                return state == null ? 0 : interner.tokenOf(state);
            }, target);
            if (!ok) target.discard();
            return ok;
        } catch (Throwable failure) {
            target.discard();
            target.reset();
            return false;
        }
    }
}
