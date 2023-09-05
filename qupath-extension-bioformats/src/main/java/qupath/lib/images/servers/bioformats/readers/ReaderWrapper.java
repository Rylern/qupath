package qupath.lib.images.servers.bioformats.readers;

import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

import java.nio.ByteOrder;
import java.util.Optional;

/**
 * <p>
 *     Wrapper around an image reader. It should be used with a {@link ReaderPool}.
 *     It is suited for readers that return arrays of bytes when reading pixel values.
 * </p>
 * <p>A reader must be {@link #close() closed} once no longer used.</p>
 */
public interface ReaderWrapper extends AutoCloseable {

    /**
     * <p>Reads the pixel values of the tile of an image.</p>
     * <p>
     *     When overriding this function, make it {@code synchronized} if the
     *     underlying reader is not thread-safe.
     * </p>
     *
     * @param tileRequest  the parameters defining the tile
     * @param series  some images contain multiple image stacks or experiments within one file.
     *                The one to use is defined by this parameter
     * @return the pixel values corresponding to these parameters, an empty Optional if the operation failed
     */
    Optional<byte[][]> read(TileRequest tileRequest, int series);

    /**
     * <p>
     *     Returns the pixel values of an 'associated image', e.g. a thumbnail or a slide overview images.
     *     These pixels are not meant to be analyzed.
     * </p>
     * <p>
     *     When overriding this function, make it {@code synchronized} if the
     *     underlying reader is not thread-safe.
     * </p>
     *
     * @param series  some images contain multiple image stacks or experiments within one file.
     *                The one to use is defined by this parameter
     * @return the pixel values corresponding to this parameter, an empty Optional if the operation failed
     */
    Optional<byte[]> read(int series);

    /**
     * @return the size (in pixels) of the x-dimension of the image, i.e. its width
     */
    int getSizeX();

    /**
     * @return the size (in pixels) of the y-dimension of the image, i.e. its height
     */
    int getSizeY();

    /**
     * @return the byte order of the arrays returned by {@link #read(TileRequest, int)}
     */
    ByteOrder getByteOrder();

    /**
     * @return the pixel type of the image
     */
    PixelType getPixelType();

    /**
     * @return whether data returned by {@link #read(TileRequest, int)} and {@link #read(int)}
     * should be normalized
     */
    boolean isNormalized();

    /**
     * @return whether channels in the data returned by {@link #read(TileRequest, int)} and {@link #read(int)}
     * are interleaved
     */
    boolean isInterleaved();

    /**
     * @return whether the image planes are indexed color
     */
    boolean isIndexed();

    /**
     * @return the effective size of the C dimension of the image. This is not always the number of channels,
     * for example RGB values can be stored in one effective channel
     */
    int getEffectiveNumberOfChannels();

    /**
     * @return the number of channels returned by {@link #read(TileRequest, int)} and {@link #read(int)}
     */
    int getRGBChannelCount();

    /**
     * Get the number of channels of an image. This doesn't refer to how data is actually stored.
     * For example, the number of channels of an RGB image is always 3, no matter how
     * it's stored.
     *
     * @param imageIndex  some images contain multiple image stacks or experiments within one file.
     *                    The one to use is defined by this parameter
     * @return the number of channels
     */
    int getChannelCount(int imageIndex);

    /**
     * Get the number of separate planes in this image (see the {@code SamplesPerPixel}
     * property of TIFF files).
     *
     * @param imageIndex  some images contain multiple image stacks or experiments within one file.
     *                    The one to use is defined by this parameter
     * @param channelIndex  the channel of the image
     * @return the number of samples per pixel
     */
    int getChannelSamplesPerPixel(int imageIndex, int channelIndex);

    /**
     * @return the 8-bit color lookup table associated with the image.
     * It can be null (for example for images with a format different from 8-bit)
     */
    byte[][] get8BitLookupTable();

    /**
     * @return the 16-bit color lookup table associated with the image.
     * It can be null (for example for images with a format different from 16-bit)
     */
    short[][] get16BitLookupTable();
}
