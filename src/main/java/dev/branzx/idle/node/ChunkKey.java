package dev.branzx.idle.node;

public record ChunkKey(String world, int x, int z) {

    public ChunkKey north() {
        return new ChunkKey(world, x, z - 1);
    }

    public ChunkKey south() {
        return new ChunkKey(world, x, z + 1);
    }

    public ChunkKey east() {
        return new ChunkKey(world, x + 1, z);
    }

    public ChunkKey west() {
        return new ChunkKey(world, x - 1, z);
    }

    public ChunkKey[] neighbors() {
        return new ChunkKey[] {north(), south(), east(), west()};
    }
}
