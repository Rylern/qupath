package qupath.lib.images.servers.bioformats.readers;

import qupath.lib.images.servers.TileRequest;

import java.awt.image.*;
import java.io.IOException;

/**
 * <p>
 *     Wrapper around an image reader. It should be used with a {@link ReaderPool}.
 * </p>
 * <p>A reader must be {@link #close() closed} once no longer used.</p>
 */
public interface ReaderWrapper<T> extends AutoCloseable {

    /**
     * Reads a tile from the image.
     *
     * @param tileRequest  the parameters defining the tile
     * @param channels  the channels of this image to retrieve
     * @param colorModel  the color model to use with this image
     * @param series  some images contain multiple image stacks or experiments within one file.
     *                The one to use is defined by this parameter. This parameter is ignored
     *                if the reader only supports one image
     * @return the image corresponding to these parameters
     * @throws IOException when a reading error occurs
     */
    T getImage(TileRequest tileRequest, int[] channels, boolean isRGB, ColorModel colorModel, int series) throws IOException;

    /**
     * Returns an 'associated image', e.g. a thumbnail or a slide overview images.
     * This image is not meant to be analyzed.
     *
     * @param series  some images contain multiple image stacks or experiments within one file.
     *                The one to use is defined by this parameter
     * @return the image corresponding to this parameter
     * @throws IOException when a reading error occurs
     */
    T getImage(int series) throws IOException;
}
