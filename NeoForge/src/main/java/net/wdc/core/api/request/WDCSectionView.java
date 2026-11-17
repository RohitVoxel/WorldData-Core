package net.wdc.core.api.request;

import net.wdc.core.world.storage.WDCSection;

import java.util.List;
import java.util.function.IntConsumer;

public final class WDCSectionView {

    private final WDCSection section;

    WDCSectionView(WDCSection section) {
        this.section = section;
    }

    public int sectionY() { return section.sectionY(); }

    public int occupiedCount() { return section.occupiedCount(); }

    public int paletteSize() { return section.paletteSize(); }

    public int getBlock(int localX, int localY, int localZ) {
        return section.getBlock(localX, localY, localZ);
    }

    public List<WDCSection.StateCount> stateCounts() { return section.stateCounts(); }

    public char[] positionBuffer(int globalStateId) {
        return section.positionBuffer(globalStateId);
    }

    public int positionCount(int globalStateId) {
        return section.positionCount(globalStateId);
    }

    public int stateEntryLimit() { return section.stateEntryLimit(); }

    public int stateEntryId(int entry) { return section.stateEntryId(entry); }

    public int stateEntryCount(int entry) { return section.stateEntryCount(entry); }

    public int slotAt(int packedPosition) { return section.slotAt(packedPosition); }

    public int collectPositionsAndSlots(boolean[] matchBySlot, int fromPosition, int toPosition, char[] positions, char[] slots) {
        return section.collectPositionsAndSlots(matchBySlot, fromPosition, toPosition, positions, slots);
    }

    public int collectPositionsBySlot(boolean[] matchBySlot, int fromPosition, int toPosition, char[] out) {
        return section.collectPositionsBySlot(matchBySlot, fromPosition, toPosition, out);
    }

    public void countSlotsInRange(int fromPosition, int toPosition, int[] perSlot) {
        section.countSlotsInRange(fromPosition, toPosition, perSlot);
    }

    public void countSlotsInRangeMasked(int fromPosition, int toPosition, int[] perSlot, byte[] columnTable) {
        section.countSlotsInRangeMasked(fromPosition, toPosition, perSlot, columnTable);
    }

    public void copySlotRange(int fromPosition, int toPosition, char[] out) {
        section.copySlotRange(fromPosition, toPosition, out);
    }

    public void forEachPositionOfState(int globalStateId, IntConsumer consumer) {
        section.forEachPositionOfState(globalStateId, consumer);
    }
}
