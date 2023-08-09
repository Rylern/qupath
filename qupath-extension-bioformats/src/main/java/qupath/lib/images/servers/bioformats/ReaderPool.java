package qupath.lib.images.servers.bioformats;

import loci.common.DataTools;
import loci.formats.IFormatReader;
import loci.formats.gui.AWTImageTools;
import loci.formats.gui.Index16ColorModel;
import loci.formats.gui.SignedColorModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

import java.awt.image.*;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * <p>
 *     Abstract class that can read pixel values of an image using several readers in parallel.
 *     It is suited for readers that return arrays of bytes when reading pixel values.
 * </p>
 * <p>
 *     A class extending this class must define how to fetch pixel values.
 * </p>
 * <p>
 *     Each actual reader should be wrapped with the {@link ReaderWrapper} interface, which should also be implemented.
 * </p>
 * <p>A pool of readers must be {@link #close() closed} once no longer used.</p>
 */
public abstract class ReaderPool implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ReaderPool.class);
    private final static int MAX_NUMBER_OF_READERS = 128;
    private final static Cleaner cleaner = Cleaner.create();
    private final ArrayBlockingQueue<ReaderWrapper> availableReaderWrappers = new ArrayBlockingQueue<>(MAX_NUMBER_OF_READERS);
    private final List<Cleaner.Cleanable> cleanables = new ArrayList<>();
    private volatile boolean isClosed = false;
    private ReaderWrapper mainReaderWrapper;
    private ForkJoinTask<?> task;

    /**
     * @return a new reader wrapper, or an empty Optional if the creation failed
     */
    abstract protected Optional<? extends ReaderWrapper> createReaderWrapper();

    /**
     * @return the maximum number of readers this pool can create
     */
    abstract protected int getMaxNumberOfReaders();

    @Override
    public void close() {
        isClosed = true;

        if (task != null && !task.isDone()) {
            task.cancel(true);
        }

        for (var cleanable : cleanables) {
            try {
                cleanable.clean();
            } catch (Exception e) {
                logger.error("Exception during cleanup", e);
            }
        }
    }

    /**
     * Reads a single tile image.
     *
     * @param tileRequest  the parameters defining the tile
     * @param series  some images contain multiple image stacks or experiments within one file.
     *                The one to use is defined by this parameter
     * @param numberOfChannels  the number of channels of this image
     * @param isRGB  whether the image has RGB pixels
     * @param colorModel  the color model to use with this image
     * @return the image corresponding to these parameters, an empty Optional if the operation failed
     */
    public Optional<BufferedImage> openImage(TileRequest tileRequest, int series, int numberOfChannels, boolean isRGB, ColorModel colorModel) {
        if (tileRequest.getTileWidth() <= 0 || tileRequest.getTileHeight() <= 0) {
            logger.error("Unable to request pixels for region with down sampled size " + tileRequest.getTileWidth() + " x " + tileRequest.getTileHeight());
            return Optional.empty();
        } else {
            var readerWrapper = getNextReaderWrapper();
            if (readerWrapper.isPresent()) {
                var bytes = readerWrapper.get().read(tileRequest, series);
                availableReaderWrappers.add(readerWrapper.get());

                if (bytes.isPresent()) {
                    if ((readerWrapper.get().isRGB() && isRGB) || numberOfChannels == 1) {
                        return Optional.ofNullable(openImage(
                                bytes.get()[0],
                                tileRequest.getTileWidth(),
                                tileRequest.getTileHeight(),
                                readerWrapper.get().getByteOrder(),
                                readerWrapper.get().isNormalized(),
                                readerWrapper.get().isInterleaved(),
                                readerWrapper.get().isIndexed(),
                                readerWrapper.get().getPixelType(),
                                readerWrapper.get().getRGBChannelCount(),
                                readerWrapper.get().get8BitLookupTable(),
                                readerWrapper.get().get16BitLookupTable()
                        ));
                    } else {
                        try {
                            DataBuffer dataBuffer = createBuffer(
                                    bytes.get(),
                                    readerWrapper.get().getPixelType(),
                                    readerWrapper.get().getByteOrder(),
                                    readerWrapper.get().isNormalized()
                            );

                            WritableRaster raster = WritableRaster.createWritableRaster(
                                    createSampleModel(
                                            readerWrapper.get(),
                                            tileRequest,
                                            dataBuffer,
                                            numberOfChannels,
                                            series
                                    ),
                                    dataBuffer,
                                    null
                            );

                            return Optional.of(new BufferedImage(colorModel, raster, false, null));
                        } catch (Exception e) {
                            logger.error("Could not parse pixel data", e);
                            return Optional.empty();
                        }
                    }
                } else {
                    return Optional.empty();
                }
            } else {
                logger.error("Reader is null - was the image already closed?");
                return Optional.empty();
            }
        }
    }

    /**
     * Returns an 'associated image', e.g. a thumbnail or a slide overview images.
     * This image is not meant to be analyzed.
     *
     * @param series  some images contain multiple image stacks or experiments within one file.
     *                The one to use is defined by this parameter
     * @return the image corresponding to this parameter, an empty Optional if the operation failed
     */
    public Optional<BufferedImage> openSeries(int series) {
        var readerWrapper = getNextReaderWrapper();
        if (readerWrapper.isPresent()) {
            try {
                return readerWrapper.get().read(series).map(value -> openImage(
                        value,
                        readerWrapper.get().getSizeX(),
                        readerWrapper.get().getSizeY(),
                        readerWrapper.get().getByteOrder(),
                        readerWrapper.get().isNormalized(),
                        readerWrapper.get().isInterleaved(),
                        readerWrapper.get().isIndexed(),
                        readerWrapper.get().getPixelType(),
                        readerWrapper.get().getRGBChannelCount(),
                        readerWrapper.get().get8BitLookupTable(),
                        readerWrapper.get().get16BitLookupTable()
                ));
            } finally {
                availableReaderWrappers.add(readerWrapper.get());
            }
        } else {
            logger.error("Reader is null - was the image already closed?");
            return Optional.empty();
        }
    }

    /**
     * <p>
     *     Get the the first reader wrapper created by this pool.
     *     It can be used to manually perform other operations such as fetching metadata.
     * </p>
     * <p>
     *     The reader of the returned reader wrapper may not be thread safe, so it's safer to use
     *     it with the {@code synchronized} keyword.
     * </p>
     *
     * @return the main reader wrapper used by this pool, or an empty Optional if its creation failed
     */
    protected Optional<ReaderWrapper> getMainReaderWrapper() {
        if (mainReaderWrapper == null) {
            var mainReaderWrapper = createAndAddReaderWrapper();

            if (mainReaderWrapper.isPresent()) {
                this.mainReaderWrapper = mainReaderWrapper.get();
            } else {
                logger.error("Could not create default reader wrapper");
            }
        }
        return Optional.ofNullable(mainReaderWrapper);
    }

    private Optional<? extends ReaderWrapper> createAndAddReaderWrapper() {
        if (isClosed) {
            return Optional.empty();
        } else {
            int maxReaders = getMaxReaders();
            int nReaders = availableReaderWrappers.size();

            if (nReaders >= maxReaders) {
                logger.warn("No new reader will be created (already created {}, max readers {})", nReaders, maxReaders);
                return Optional.empty();
            } else {
                var optionalReaderWrapper = createReaderWrapper();

                if (optionalReaderWrapper.isPresent()) {
                    ReaderWrapper readerWrapper = optionalReaderWrapper.get();

                    int numberOfCleanables = cleanables.size();
                    cleanables.add(cleaner.register(this, () -> {
                        String name = Integer.toString(numberOfCleanables + 1);
                        logger.debug("Cleaner " + name + " called for " + readerWrapper);

                        try {
                            readerWrapper.close();
                        } catch (Exception e) {
                            logger.error("Error when calling cleaner for " + name, e);
                        }
                    }));

                    availableReaderWrappers.add(readerWrapper);
                }

                return optionalReaderWrapper;
            }
        }
    }

    private Optional<ReaderWrapper> getNextReaderWrapper() {
        var nextReader = availableReaderWrappers.poll();
        if (nextReader == null) {
            synchronized (this) {
                if (!isClosed && (task == null || task.isDone()) && availableReaderWrappers.size() < getMaxReaders()) {
                    task = ForkJoinPool.commonPool().submit(this::createAndAddReaderWrapper);
                }
            }

            if (isClosed) {
                return Optional.empty();
            } else {
                try {
                    return Optional.ofNullable(availableReaderWrappers.poll(60, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    logger.error("Interrupted exception when awaiting next queued reader", e);
                    return isClosed ? Optional.empty() : getMainReaderWrapper();
                }
            }
        } else {
            return Optional.of(nextReader);
        }
    }

    private DataBuffer createBuffer(byte[][] bytes, PixelType pixelType, ByteOrder byteOrder, boolean normalizeFloats) {
        return switch (pixelType) {
            case UINT8 -> new DataBufferByte(bytes, bytes[0].length);
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
            case INT32 -> {
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
            // TODO: Consider conversion to closest supported pixel type
            default -> throw new UnsupportedOperationException("Unsupported pixel type " + pixelType);
        };
    }

    private SampleModel createSampleModel(
            ReaderWrapper reader,
            TileRequest tileRequest,
            DataBuffer dataBuffer,
            int numberOfChannels,
            int series
    ) {
        int effectiveC = reader.getEffectiveNumberOfChannels();

        if (effectiveC == 1 && numberOfChannels > 1) {
            // Handle channels stored in the same plane
            int[] offsets = new int[numberOfChannels];
            if (reader.isInterleaved()) {
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

            int channelCount = reader.getChannelCount(series);
            for (int cInd = 0; cInd < channelCount; cInd++) {
                int nSamples = reader.getChannelSamplesPerPixel(series, cInd);
                for (int s = 0; s < nSamples; s++) {
                    bandInds[ind] = cInd;
                    if (reader.isInterleaved()) {
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
     * Create an image from the supplied parameters (adapted from
     * {@link AWTImageTools#openImage(byte[], IFormatReader, int, int, boolean)}).
     */
    private static BufferedImage openImage(
            byte[] bytes,
            int width,
            int height,
            ByteOrder byteOrder,
            boolean isNormalized,
            boolean isInterleaved,
            boolean isIndexed,
            PixelType pixelType,
            int rgbChannelCount,
            byte[][] lookupTable8Bit,
            short[][] lookupTable16Bit
    ) {
        try {
            boolean isLittleEndian = byteOrder.equals(ByteOrder.LITTLE_ENDIAN);

            if (pixelType == PixelType.FLOAT32) {
                float[] f = (float[]) DataTools.makeDataArray(bytes, 4, true, isLittleEndian);
                if (isNormalized) f = DataTools.normalizeFloats(f);
                return AWTImageTools.makeImage(f, width, height, rgbChannelCount, isInterleaved);
            }
            else if (pixelType == PixelType.FLOAT64) {
                double[] d = (double[]) DataTools.makeDataArray(bytes, 8, true, isLittleEndian);
                if (isNormalized) d = DataTools.normalizeDoubles(d);
                return AWTImageTools.makeImage(d, width, height, rgbChannelCount, isInterleaved);
            }

            boolean signed = pixelType.isSignedInteger();
            ColorModel model = null;

            if (signed) {
                if (pixelType == PixelType.INT8) {
                    model = new SignedColorModel(8, DataBuffer.TYPE_BYTE, rgbChannelCount);
                }
                else if (pixelType == PixelType.INT16) {
                    model = new SignedColorModel(16, DataBuffer.TYPE_SHORT, rgbChannelCount);
                }
                else if (pixelType == PixelType.INT32) {
                    model = new SignedColorModel(32, DataBuffer.TYPE_INT, rgbChannelCount);
                }
            }

            int bpp = pixelType.getBytesPerPixel();
            BufferedImage b = AWTImageTools.makeImage(bytes, width, height, rgbChannelCount,
                    isInterleaved, bpp, false, isLittleEndian, signed);
            if (b == null) {
                throw new IOException("Could not construct BufferedImage");
            }

            if (isIndexed && rgbChannelCount == 1) {
                if (pixelType == PixelType.UINT8 || pixelType == PixelType.INT8) {
                    if (lookupTable8Bit != null && lookupTable8Bit.length > 0 && lookupTable8Bit[0] != null) {
                        int len = lookupTable8Bit[0].length;
                        byte[] dummy = lookupTable8Bit.length < 3 ? new byte[len] : null;
                        byte[] red = lookupTable8Bit[0];
                        byte[] green = lookupTable8Bit.length >= 2 ? lookupTable8Bit[1] : dummy;
                        byte[] blue = lookupTable8Bit.length >= 3 ? lookupTable8Bit[2] : dummy;
                        model = new IndexColorModel(8, len, red, green, blue);
                    }
                }
                else if (pixelType == PixelType.UINT16 ||
                        pixelType == PixelType.INT16)
                {
                    if (lookupTable16Bit != null && lookupTable16Bit.length > 0 && lookupTable16Bit[0] != null) {
                        model = new Index16ColorModel(16, lookupTable16Bit[0].length, lookupTable16Bit,
                                isLittleEndian);
                    }
                }
            }

            if (model != null) {
                WritableRaster raster = Raster.createWritableRaster(b.getSampleModel(),
                        b.getRaster().getDataBuffer(), null);
                b = new BufferedImage(model, raster, false, null);
            }

            return b;
        } catch (Exception e) {
            logger.error("Could not create image", e);
            return null;
        }
    }

    private int getMaxReaders() {
        return Math.min(MAX_NUMBER_OF_READERS, getMaxNumberOfReaders());
    }
}
