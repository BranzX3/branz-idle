package dev.branzx.idlefarm.schematic;

/** Position relative to a building origin (chunk-center ground block). */
public record RelPos(int x, int y, int z) {

    public String serialize() {
        return x + "," + y + "," + z;
    }

    public static RelPos deserialize(String value) {
        String[] parts = value.split(",");
        return new RelPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }
}
