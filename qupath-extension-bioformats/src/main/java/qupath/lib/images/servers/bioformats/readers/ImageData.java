package qupath.lib.images.servers.bioformats.readers;

/**
 * Represents pixel values of an image.
 *
 * @param data  the image pixel values
 * @param width  the width of the image
 * @param height  the height of the image
 * @param isInterleaved  whether pixels are interleaved
 */
public record ImageData (byte[] data, int width, int height, boolean isInterleaved) {}
