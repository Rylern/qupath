package qupath.lib.images.servers.bioformats.readers;

import loci.common.DataTools;
import loci.formats.IFormatReader;
import loci.formats.gui.AWTImageTools;
import loci.formats.gui.Index16ColorModel;
import loci.formats.gui.SignedColorModel;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

import java.awt.image.*;
import java.io.IOException;
import java.nio.*;
import java.util.Optional;

/**
 * <p>
 *     Wrapper suited for readers that return arrays of bytes when reading pixel values.
 * </p>
 */
public abstract class OMEReaderWrapper implements ReaderWrapper<BufferedImage> {

    @Override
    public BufferedImage getImage(TileRequest tileRequest, int[] channels, boolean isRGB, ColorModel colorModel, int series) throws IOException {
        byte[][] pixelValues = getPixelValues(tileRequest, series);

        if ((isRGB() && isRGB) || channels.length == 1) {
            return openImage(pixelValues[0], tileRequest.getTileWidth(), tileRequest.getTileHeight(), isInterleaved());
        } else {
            DataBuffer dataBuffer = bytesToDataBuffer(
                    pixelValues,
                    getPixelType(),
                    getByteOrder(),
                    isNormalized()
            );
            WritableRaster raster = WritableRaster.createWritableRaster(
                    createSampleModel(
                            tileRequest,
                            dataBuffer,
                            channels.length,
                            series
                    ),
                    dataBuffer,
                    null
            );

            return new BufferedImage(colorModel, raster, false, null);
        }
    }

    @Override
    public BufferedImage getImage(int series) throws IOException {
        ImageInfo imageInfo = getPixelValues(series);

        return openImage(
                imageInfo.data(),
                imageInfo.width(),
                imageInfo.height(),
                imageInfo.isInterleaved()
        );
    }

    /**
     * <p>
     *     Reads the pixel values of the tile of an image.
     *     The returned value is a 2-dimensional byte array with the first dimension representing
     *     the channel and the second dimension representing the position of the pixel
     *     value in the tile.
     * </p>
     *
     * @param tileRequest  the parameters defining the tile
     * @param series  some images contain multiple image stacks or experiments within one file.
     *                The one to use is defined by this parameter
     * @return the pixel values corresponding to these parameters
     * @throws IOException when a reading error occurs
     */
    protected abstract byte[][] getPixelValues(TileRequest tileRequest, int series) throws IOException;

    /**
     * <p>
     *     Returns the pixel values of an 'associated image', e.g. a thumbnail or a slide overview images.
     *     These pixels are not meant to be analyzed.
     * </p>
     *
     * @param series  some images contain multiple image stacks or experiments within one file.
     *                The one to use is defined by this parameter
     * @return the pixel values corresponding to this parameter
     * @throws IOException when a reading error occurs
     */
    protected abstract ImageInfo getPixelValues(int series) throws IOException;

    /**
     * @return the byte order of the arrays returned by {@link #getPixelValues(TileRequest, int)}
     */
    protected abstract ByteOrder getByteOrder();

    /**
     * @return the pixel type of the image
     */
    protected abstract PixelType getPixelType();

    /**
     * @return whether data returned by {@link #getPixelValues(TileRequest, int)} and {@link #getPixelValues(int)}
     * should be normalized
     */
    protected abstract boolean isNormalized();

    /**
     * @return whether channels in the data returned by {@link #getPixelValues(TileRequest, int)} and {@link #getPixelValues(int)}
     * are interleaved
     */
    protected abstract boolean isInterleaved();

    /**
     * @return whether the image is indexed (see <a href="https://en.wikipedia.org/wiki/Indexed_color">Indexed color</a>)
     */
    protected abstract boolean isIndexed();

    /**
     * @return whether the reader consider the image to be RGB
     */
    protected abstract boolean isRGB();

    /**
     * @return the effective size of the C dimension of the image. This is not always the number of channels,
     * for example RGB values can be stored in one effective channel
     */
    protected abstract int getEffectiveNumberOfChannels();

    /**
     * @return the number of channels returned by {@link #getPixelValues(TileRequest, int)} and {@link #getPixelValues(int)}
     */
    protected abstract int getRGBChannelCount();

    /**
     * Get the number of channels of an image. This doesn't refer to how data is actually stored.
     * For example, the number of channels of an RGB image is always 3, no matter how
     * it's stored.
     *
     * @param imageIndex  some images contain multiple image stacks or experiments within one file.
     *                    The one to use is defined by this parameter
     * @return the number of channels
     */
    protected abstract int getChannelCount(int imageIndex);

    /**
     * Get the number of separate planes in this image (see the {@code SamplesPerPixel}
     * property of TIFF files).
     *
     * @param imageIndex  some images contain multiple image stacks or experiments within one file.
     *                    The one to use is defined by this parameter
     * @param channelIndex  the channel of the image
     * @return the number of samples per pixel
     */
    protected abstract int getChannelSamplesPerPixel(int imageIndex, int channelIndex);

    /**
     * <p>
     *     Retrieves the 8-bit color lookup table associated with the image (if it's
     *     an indexed 8-bit image).
     * </p>
     * <p>
     *     The returned value is a 2-dimensional byte array where the first dimension represents
     *     the color (0 for red, 1 for green, 2 for blue) and the second dimension represents
     *     the index. For example, res[1][3] will return the green component of the fourth index.
     * </p>
     *
     * @return the 8-bit color lookup table associated with the image, or an empty Optional
     *  if the table doesn't exist
     * @throws IllegalStateException for non-indexed images with a format different from 8-bit
     * @throws IOException if the table couldn't be retrieved
     */
    protected Optional<byte[][]> get8BitLookupTable() throws IOException {
        return Optional.empty();
    }

    /**
     * 16-bit version of {@link #get8BitLookupTable()}.
     */
    protected Optional<short[][]> get16BitLookupTable() throws IOException {
        return Optional.empty();
    }

    private DataBuffer bytesToDataBuffer(byte[][] bytes, PixelType pixelType, ByteOrder byteOrder, boolean normalizeFloats) {
        return switch (pixelType) {
            case UINT8, INT8 -> new DataBufferByte(bytes, bytes[0].length);
            case UINT16, INT16 -> {
                short[][] array = new short[bytes.length][];
                for (int i = 0; i < bytes.length; i++) {
                    ShortBuffer buffer = ByteBuffer.wrap(bytes[i]).order(byteOrder).asShortBuffer();
                    array[i] = new short[buffer.limit()];
                    buffer.get(array[i]);
                }
                yield pixelType == PixelType.UINT16 ?
                        new DataBufferUShort(array, bytes[0].length / 2) :
                        new DataBufferShort(array, bytes[0].length / 2);
            }
            case UINT32, INT32 -> {
                int[][] array = new int[bytes.length][];
                for (int i = 0; i < bytes.length; i++) {
                    IntBuffer buffer = ByteBuffer.wrap(bytes[i]).order(byteOrder).asIntBuffer();
                    array[i] = new int[buffer.limit()];
                    buffer.get(array[i]);
                }
                yield new DataBufferInt(array, bytes[0].length / 4);
            }
            case FLOAT32 -> {
                float[][] array = new float[bytes.length][];
                for (int i = 0; i < bytes.length; i++) {
                    FloatBuffer buffer = ByteBuffer.wrap(bytes[i]).order(byteOrder).asFloatBuffer();
                    array[i] = new float[buffer.limit()];
                    buffer.get(array[i]);

                    if (normalizeFloats) {
                        array[i] = DataTools.normalizeFloats(array[i]);
                    }
                }
                yield new DataBufferFloat(array, bytes[0].length / 4);
            }
            case FLOAT64 -> {
                double[][] array = new double[bytes.length][];
                for (int i = 0; i < bytes.length; i++) {
                    DoubleBuffer buffer = ByteBuffer.wrap(bytes[i]).order(byteOrder).asDoubleBuffer();
                    array[i] = new double[buffer.limit()];
                    buffer.get(array[i]);
                    if (normalizeFloats) {
                        array[i] = DataTools.normalizeDoubles(array[i]);
                    }
                }
                yield new DataBufferDouble(array, bytes[0].length / 8);
            }
        };
    }

    private SampleModel createSampleModel(
            TileRequest tileRequest,
            DataBuffer dataBuffer,
            int numberOfChannels,
            int series
    ) {
        int effectiveC = getEffectiveNumberOfChannels();

        if (effectiveC == 1 && numberOfChannels > 1) {
            // Handle channels stored in the same plane
            int[] offsets = new int[numberOfChannels];
            if (isInterleaved()) {
                for (int channel = 0; channel < numberOfChannels; channel++)
                    offsets[channel] = channel;

                return new PixelInterleavedSampleModel(
                        dataBuffer.getDataType(),
                        tileRequest.getTileWidth(),
                        tileRequest.getTileHeight(),
                        numberOfChannels,
                        numberOfChannels * tileRequest.getTileWidth(),
                        offsets
                );
            } else {
                for (int channel = 0; channel < numberOfChannels; channel++)
                    offsets[channel] = channel * tileRequest.getTileWidth() * tileRequest.getTileHeight();

                return new ComponentSampleModel(
                        dataBuffer.getDataType(),
                        tileRequest.getTileWidth(),
                        tileRequest.getTileHeight(),
                        1,
                        tileRequest.getTileWidth(),
                        offsets
                );
            }
        } else if (numberOfChannels > effectiveC) {
            // Handle multiple bands, but still interleaved
            // See https://forum.image.sc/t/qupath-cant-open-polarized-light-scans/65951
            int[] offsets = new int[numberOfChannels];
            int[] bandInds = new int[numberOfChannels];
            int ind = 0;

            int channelCount = getChannelCount(series);
            for (int cInd = 0; cInd < channelCount; cInd++) {
                int nSamples = getChannelSamplesPerPixel(series, cInd);
                for (int s = 0; s < nSamples; s++) {
                    bandInds[ind] = cInd;
                    if (isInterleaved()) {
                        offsets[ind] = s;
                    } else {
                        offsets[ind] = s * tileRequest.getTileWidth() * tileRequest.getTileHeight();
                    }
                    ind++;
                }
            }

            // TODO: Check this! It works for the only test image I have... (2 channels with 3 samples each)
            // I would guess it fails if pixelStride does not equal nSamples, and if nSamples is different for different 'channels' -
            // but I don't know if this occurs in practice.
            // If it does, I don't see a way to use a ComponentSampleModel... which could complicate things quite a bit
            int pixelStride = numberOfChannels / effectiveC;
            int scanlineStride = pixelStride * tileRequest.getTileWidth();
            return new ComponentSampleModel(
                    dataBuffer.getDataType(),
                    tileRequest.getTileWidth(),
                    tileRequest.getTileHeight(),
                    pixelStride,
                    scanlineStride,
                    bandInds,
                    offsets
            );
        } else {
            // Merge channels on different planes
            return new BandedSampleModel(
                    dataBuffer.getDataType(),
                    tileRequest.getTileWidth(),
                    tileRequest.getTileHeight(),
                    numberOfChannels
            );
        }
    }

    /**
     * <p>
     *     Create an image from the supplied parameters (adapted from
     *     {@link AWTImageTools#openImage(byte[], IFormatReader, int, int, boolean)}).
     * </p>
     *
     * @param bytes  the pixel values (the image must have a single effective channel)
     * @param width  the width of the image
     * @param height  the height of the image
     * @param isInterleaved  whether pixel values are interleaved
     * @return the created image
     * @throws IOException when an error occurs while creating the image
     */
    private BufferedImage openImage(
            byte[] bytes,
            int width,
            int height,
            boolean isInterleaved
    ) throws IOException {
        boolean isLittleEndian = getByteOrder().equals(ByteOrder.LITTLE_ENDIAN);

        if (getPixelType() == PixelType.FLOAT32) {
            float[] f = (float[]) DataTools.makeDataArray(bytes, 4, true, isLittleEndian);
            if (isNormalized())
                f = DataTools.normalizeFloats(f);
            return AWTImageTools.makeImage(f, width, height, getRGBChannelCount(), isInterleaved);
        }
        else if (getPixelType() == PixelType.FLOAT64) {
            double[] d = (double[]) DataTools.makeDataArray(bytes, 8, true, isLittleEndian);
            if (isNormalized())
                d = DataTools.normalizeDoubles(d);
            return AWTImageTools.makeImage(d, width, height, getRGBChannelCount(), isInterleaved);
        }

        boolean signed = getPixelType().isSignedInteger();
        ColorModel model = null;

        if (signed) {
            if (getPixelType() == PixelType.INT8) {
                model = new SignedColorModel(8, DataBuffer.TYPE_BYTE, getRGBChannelCount());
            }
            else if (getPixelType() == PixelType.INT16) {
                model = new SignedColorModel(16, DataBuffer.TYPE_SHORT, getRGBChannelCount());
            }
            else if (getPixelType() == PixelType.INT32) {
                model = new SignedColorModel(32, DataBuffer.TYPE_INT, getRGBChannelCount());
            }
        }

        int bpp = getPixelType().getBytesPerPixel();
        BufferedImage b = AWTImageTools.makeImage(
                bytes,
                width,
                height,
                getRGBChannelCount(),
                isInterleaved,
                bpp,
                false,
                isLittleEndian,
                signed
        );
        if (b == null) {
            throw new IOException("Could not construct BufferedImage");
        }

        if (isIndexed() && getRGBChannelCount() == 1) {
            if (getPixelType() == PixelType.UINT8 || getPixelType() == PixelType.INT8) {
                byte[][] lookupTable8Bit = null;
                try {
                    lookupTable8Bit = get8BitLookupTable().orElse(null);
                } catch (Exception ignored) {}

                if (lookupTable8Bit != null && lookupTable8Bit.length > 0 && lookupTable8Bit[0] != null) {
                    int len = lookupTable8Bit[0].length;
                    byte[] dummy = lookupTable8Bit.length < 3 ? new byte[len] : null;
                    byte[] red = lookupTable8Bit[0];
                    byte[] green = lookupTable8Bit.length >= 2 ? lookupTable8Bit[1] : dummy;
                    byte[] blue = lookupTable8Bit.length >= 3 ? lookupTable8Bit[2] : dummy;
                    model = new IndexColorModel(8, len, red, green, blue);
                }
            } else if (getPixelType() == PixelType.UINT16 || getPixelType() == PixelType.INT16) {
                short[][] lookupTable16Bit = null;
                try {
                    lookupTable16Bit = get16BitLookupTable().orElse(null);
                } catch (Exception ignored) {}

                if (lookupTable16Bit != null && lookupTable16Bit.length > 0 && lookupTable16Bit[0] != null) {
                    model = new Index16ColorModel(
                            16,
                            lookupTable16Bit[0].length,
                            lookupTable16Bit,
                            isLittleEndian
                    );
                }
            }
        }

        if (model != null) {
            WritableRaster raster = Raster.createWritableRaster(
                    b.getSampleModel(),
                    b.getRaster().getDataBuffer(),
                    null
            );
            b = new BufferedImage(model, raster, false, null);
        }

        return b;
    }
}
