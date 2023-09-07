package qupath.lib.images.servers.bioformats.readers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.TileRequest;

import java.awt.image.*;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>
 *     A pool of readers that can read pixel values of an image in parallel.
 *     It must be {@link #close() closed} once no longer used.
 * </p>
 */
public class ReaderPool implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ReaderPool.class);
    private final static int MIN_NUMBER_OF_READERS = 2;
    private final static int MAX_NUMBER_OF_READERS = 128;
    private final static int READER_AVAILABILITY_WAITING_TIME = 60;
    private final static TimeUnit READER_AVAILABILITY_WAITING_TIME_UNIT = TimeUnit.SECONDS;
    private final static Cleaner cleaner = Cleaner.create();
    private final List<Cleaner.Cleanable> cleanables = new ArrayList<>();
    private final AtomicInteger numberOfReaderWrappers = new AtomicInteger(0);
    private volatile boolean isClosed = false;
    private final int maxNumberOfReaders;
    private final ArrayBlockingQueue<ReaderWrapper> availableReaderWrappers;
    private final Callable<ReaderWrapper> readerWrapperSupplier;
    private ReaderWrapper dedicatedReaderWrapper;

    /**
     * Creates a new pool of readers.
     *
     * @param maxNumberOfReaders  the maximum number of readers this pool can create.
     *                            Its value should be at least 2 (the dedicated reader wrapper + a reader wrapper to read the image)
     * @param readerWrapperSupplier  the function that supplies {@link ReaderWrapper ReaderWrappers} to this pool.
     *                               The supplied reader wrappers will be automatically closed when this reader pool is closed.
     */
    public ReaderPool(int maxNumberOfReaders, Callable<ReaderWrapper> readerWrapperSupplier) {
        if (maxNumberOfReaders < MIN_NUMBER_OF_READERS) {
            logger.warn(String.format(
                    "The specified maximum number of readers (%d) is less than %d. Setting it to %d.",
                    maxNumberOfReaders, MIN_NUMBER_OF_READERS, MIN_NUMBER_OF_READERS
            ));
            maxNumberOfReaders = MIN_NUMBER_OF_READERS;
        }

        this.maxNumberOfReaders = Math.min(MAX_NUMBER_OF_READERS, maxNumberOfReaders);
        availableReaderWrappers = new ArrayBlockingQueue<>(this.maxNumberOfReaders);
        this.readerWrapperSupplier = readerWrapperSupplier;
    }

    @Override
    public void close() {
        isClosed = true;

        for (var cleanable : cleanables) {
            try {
                cleanable.clean();
            } catch (Exception e) {
                logger.error("Exception during cleanup", e);
            }
        }
    }

    /**
     * <p>
     *     Get a dedicated reader wrapper created by this pool. It can be used to
     *     manually perform other operations such as fetching metadata.
     * </p>
     * <p>
     *     The returned reader wrapper won't be used by this pool; however,
     *     it will be automatically closed if this pool is closed.
     * </p>
     *
     * @return a dedicated reader wrapper
     * @throws IllegalStateException when the reader pool is already closed
     * @throws IOException when the creation of the reader wrapper fails
     * @throws InterruptedException when the wait for a reader wrapper is interrupted
     */
    public ReaderWrapper getDedicatedReaderWrapper() throws IOException, InterruptedException {
        if (dedicatedReaderWrapper == null) {
            dedicatedReaderWrapper = getNextReaderWrapper();
        }
        return dedicatedReaderWrapper;
    }

    /**
     * Reads a tile of the image.
     *
     * @param tileRequest  the parameters defining the tile
     * @param series  some images contain multiple image stacks or experiments within one file.
     *                The one to use is defined by this parameter
     * @param numberOfChannels  the number of channels of this image
     * @param colorModel  the color model to use with this image
     * @return the image corresponding to these parameters
     * @throws IllegalArgumentException when the tile dimensions are negative
     * @throws IllegalStateException when no reader is available
     * @throws IOException when a reading error occurs
     */
    public BufferedImage openImage(TileRequest tileRequest, int series, int numberOfChannels, boolean isRGB, ColorModel colorModel) throws IOException {
        if (tileRequest.getTileWidth() <= 0 || tileRequest.getTileHeight() <= 0) {
            throw new IllegalArgumentException("Unable to request pixels for region with down sampled size " + tileRequest.getTileWidth() + " x " + tileRequest.getTileHeight());
        }

        try {
            ReaderWrapper readerWrapper = getNextReaderWrapper();
            BufferedImage image = readerWrapper.getImage(tileRequest, series, numberOfChannels, isRGB, colorModel);
            availableReaderWrappers.add(readerWrapper);

            return image;
        } catch (InterruptedException e) {
            throw new IllegalStateException("Interruption while waiting for a reader to become available", e);
        }
    }

    /**
     * Returns an 'associated image', e.g. a thumbnail or a slide overview images.
     * This image is not meant to be analyzed.
     *
     * @param series  some images contain multiple image stacks or experiments within one file.
     *                The one to use is defined by this parameter
     * @return the image corresponding to this parameter
     * @throws IllegalStateException when no reader is available
     * @throws IOException when a reading error occurs
     */
    public BufferedImage openSeries(int series) throws IOException {
        try {
            var readerWrapper = getNextReaderWrapper();
            BufferedImage image = readerWrapper.getImage(series);
            availableReaderWrappers.add(readerWrapper);

            return image;
        } catch (InterruptedException e) {
            throw new IllegalStateException("Interruption while waiting for a reader to become available", e);
        }
    }

    /**
     * Retrieves a reader wrapper. If no reader is immediately available:
     * <ul>
     *     <li>If the maximal number of reader hasn't been reached, a new reader is created.</li>
     *     <li>Else, this function waits for an existing reader to become available.</li>
     * </ul>
     * @return a reader wrapper
     * @throws IllegalStateException when the reader pool is already closed or a reader could
     *  not be retrieved in time
     * @throws IOException when the creation of the reader wrapper fails
     * @throws InterruptedException when the wait for a reader wrapper is interrupted
     */
    private ReaderWrapper getNextReaderWrapper() throws IOException, InterruptedException {
        if (isClosed) {
            throw new IllegalStateException("Reader pool is closed");
        } else {
            var nextReader = availableReaderWrappers.poll();
            if (nextReader == null) {
                var newReader = addReaderWrapper();
                if (newReader.isPresent()) {
                    return newReader.get();
                } else {
                    var reader = availableReaderWrappers.poll(READER_AVAILABILITY_WAITING_TIME, READER_AVAILABILITY_WAITING_TIME_UNIT);
                    if (reader == null) {
                        throw new IllegalStateException(
                                "No reader became available within " + READER_AVAILABILITY_WAITING_TIME + " " + READER_AVAILABILITY_WAITING_TIME_UNIT
                        );
                    } else {
                        return reader;
                    }
                }
            } else {
                return nextReader;
            }
        }
    }

    /**
     * Creates and return a new reader wrapper.
     *
     * @return the newly created reader wrapper, or an empty Optional if
     *  the maximal number of reader wrappers has already been reached
     * @throws IllegalStateException when the reader pool is already closed
     * @throws IOException when the creation of the reader wrapper fails
     */
    private synchronized Optional<ReaderWrapper> addReaderWrapper() throws IOException {
        if (isClosed) {
            throw new IllegalStateException("Reader pool is closed");
        } else {
            if (numberOfReaderWrappers.get() < maxNumberOfReaders) {
                ReaderWrapper readerWrapper;
                try {
                    readerWrapper = readerWrapperSupplier.call();
                } catch (Exception e) {
                    throw new IOException(e);
                }
                numberOfReaderWrappers.incrementAndGet();

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

                return Optional.of(readerWrapper);
            } else {
                return Optional.empty();
            }
        }
    }
}