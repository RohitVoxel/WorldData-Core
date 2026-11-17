package net.wdc.core.quality.memory;

public record CellMemorySnapshot(float humidityMemory, float stormInfluence, long lastMemoryUpdateTick) {}
