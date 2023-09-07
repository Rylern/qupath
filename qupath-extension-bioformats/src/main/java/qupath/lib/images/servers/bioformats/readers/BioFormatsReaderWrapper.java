package qupath.lib.images.servers.bioformats.readers;

import loci.formats.*;
import loci.formats.in.DynamicMetadataOptions;
import loci.formats.in.MetadataOptions;
import loci.formats.meta.DummyMetadata;
import loci.formats.ome.OMEPyramidStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.bioformats.BioFormatsImageServer;
import qupath.lib.images.servers.bioformats.BioFormatsServerOptions;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link OMEReaderWrapper} around a Bio-Formats {@link IFormatReader}.
 * It supports memoization, and it is possible to retrieve its {@link IFormatReader}.
 */
public class BioFormatsReaderWrapper extends OMEReaderWrapper {

    private static final Logger logger = LoggerFactory.getLogger(BioFormatsReaderWrapper.class);
    /**
     * Define a maximum memoization file size above which parallelization is disabled.
     * This is necessary to avoid creating multiple readers that are too large (e.g. sometimes
     * a memoization file can be over 1GB...)
     */
    private static final long MAX_PARALLELIZATION_MEMO_SIZE = 1024L * 1024L * 16L;
    /**
     * Set of created temp memo files
     */
    private static final Set<File> tempMemoFiles = new HashSet<>();
    /**
     * Temporary directory for storing memoization files
     */
    private static volatile File dirMemoTemp = null;
    private final OMEPyramidStore metadata;
    private IFormatReader reader;
    private final ByteOrder byteOrder;
    private final PixelType pixelType;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private BioFormatsReaderWrapper(
            BioFormatsImageServer.BioFormatsArgs args,
            OMEPyramidStore metadata,
            ClassList<IFormatReader> classList,
            BioFormatsServerOptions options,
            String id
    ) throws IOException, FormatException {
        this.metadata = metadata;

        if (classList == null) {
            reader = new ImageReader();
        } else {
            reader = new ImageReader(classList);
        }
        reader.setFlattenedResolutions(false);

        setReaderOptions(args);

        // TODO: Warning! Memoization does not play nicely with options like
        // --bfOptions zeissczi.autostitch=false
        // in a way that options don't have an effect unless QuPath is restarted.
        int memoizationTimeMillis = options.getMemoizationTimeMillis();
        File fileMemo = null;
        boolean useTempMemoDirectory = false;

        // Check if we want to (and can) use memoization
        if (BioFormatsServerOptions.allowMemoization() && memoizationTimeMillis >= 0) {
            File dir = null;
            // Try to use a specified directory
            String pathMemoization = options.getPathMemoization();
            if (pathMemoization != null && !pathMemoization.trim().isEmpty()) {
                dir = new File(pathMemoization);
                if (!dir.isDirectory()) {
                    logger.warn("Memoization path does not refer to a valid directory, will be ignored: {}", dir.getAbsolutePath());
                    dir = null;
                }
            }
            if (dir == null) {
                try {
                    dir = getTempMemoDir();
                } catch (IOException e) {
                    logger.error("Could not get temp directory, e");
                }
                useTempMemoDirectory = dir != null;
            }
            if (dir != null) {
                try {
                    Memoizer memoizer = new Memoizer(reader, memoizationTimeMillis, dir);
                    fileMemo = memoizer.getMemoFile(id);
                    // The call to .toPath() should throw an InvalidPathException if there are illegal characters
                    // If so, we want to know that now before committing to the memoizer
                    if (fileMemo != null)
                        reader = memoizer;
                } catch (Exception e) {
                    logger.warn("Unable to use memoization: {}", e.getLocalizedMessage());
                    logger.debug(e.getLocalizedMessage(), e);
                }
            }
        }

        setMetadata(classList == null);

        var swapDimensions = args.getSwapDimensions();
        if (id != null) {
            if (fileMemo != null) {
                // If we're using a temporary directory, delete the memo file when app closes
                if (useTempMemoDirectory)
                    tempMemoFiles.add(fileMemo);

                boolean memoFileExists = fileMemo.exists();
                try {
                    if (swapDimensions != null)
                        reader = DimensionSwapper.makeDimensionSwapper(reader);
                    reader.setId(id);
                } catch (Exception e) {
                    if (memoFileExists) {
                        logger.warn("Problem with memoization file {} ({}), will try to delete it", fileMemo.getName(), e.getLocalizedMessage());
                        fileMemo.delete();
                    }
                    try {
                        reader.close();
                    } catch (IOException ex) {
                        logger.error("Error when closing reader", e);
                    }
                    if (swapDimensions != null)
                        reader = DimensionSwapper.makeDimensionSwapper(reader);
                    reader.setId(id);
                }
                long memoizationFileSize = fileMemo.length();
                if (memoizationFileSize > 0L) {
                    if (memoizationFileSize > MAX_PARALLELIZATION_MEMO_SIZE) {
                        logger.warn(String.format("The memoization file is very large (%.1f MB) - parallelization may be turned off to save memory",
                                memoizationFileSize/(1024.0*1024.0)));
                    }
                } if (memoizationFileSize == 0L)
                    logger.debug("No memoization file generated for {}", id);
                else if (!memoFileExists)
                    logger.debug(String.format("Generating memoization file %s (%.2f MB)", fileMemo.getAbsolutePath(), memoizationFileSize/1024.0/1024.0));
                else
                    logger.debug("Memoization file exists at {}", fileMemo.getAbsolutePath());
            } else {
                if (swapDimensions != null)
                    reader = DimensionSwapper.makeDimensionSwapper(reader);
                reader.setId(id);
            }
        }

        if (swapDimensions != null) {
            logger.debug("Creating DimensionSwapper for {}", swapDimensions);

            // The series needs to be set before swapping dimensions
            if (args.series >= 0)
                reader.setSeries(args.series);
            ((DimensionSwapper) reader).swapDimensions(swapDimensions);
        }

        byteOrder = reader.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        pixelType = switch (reader.getPixelType()) {
            case FormatTools.BIT, FormatTools.UINT8 -> PixelType.UINT8;
            case FormatTools.INT8 -> PixelType.INT8;
            case FormatTools.UINT16 -> PixelType.UINT16;
            case FormatTools.INT16 -> PixelType.INT16;
            case FormatTools.UINT32 -> PixelType.UINT32;
            case FormatTools.INT32 -> PixelType.INT32;
            case FormatTools.FLOAT -> PixelType.FLOAT32;
            case FormatTools.DOUBLE -> PixelType.FLOAT64;
            default -> throw new IllegalStateException("Unexpected value: " + reader.getPixelType());
        };
    }

    /**
     * Creates a new Bio-Formats reader wrapper.
     * This function should be used when creating the first reader of the pool.
     *
     * @param args  optional arguments
     * @param metadata  default metadata store for this reader
     * @param options  options that customize the behavior of the pool (e.g. memoization, max number of reader)
     * @param id  a unique identifier for the image to be opened by this pool of readers
     * @return a new Bio-Formats reader wrapper
     * @throws IOException when the reader creation fails
     */
    public static BioFormatsReaderWrapper createFirstReader(
            BioFormatsImageServer.BioFormatsArgs args,
            OMEPyramidStore metadata,
            BioFormatsServerOptions options,
            String id
    ) throws IOException {
        return createReader(
                args,
                metadata,
                null,
                options,
                id
        );
    }

    /**
     * Creates a new Bio-Formats reader wrapper.
     * This function should be used when creating another than the first reader of the pool.
     *
     * @param args  optional arguments
     * @param metadata  default metadata store for this reader
     * @param classList  specify a list of potential reader classes, to avoid a more lengthy search
     * @param options  options that customize the behavior of the pool (e.g. memoization, max number of reader)
     * @param id  a unique identifier for the image to be opened by this pool of readers
     * @return a new Bio-Formats reader wrapper
     * @throws IOException when the reader creation fails
     */
    public static BioFormatsReaderWrapper createReader(
            BioFormatsImageServer.BioFormatsArgs args,
            OMEPyramidStore metadata,
            ClassList<IFormatReader> classList,
            BioFormatsServerOptions options,
            String id
    ) throws IOException {
        try {
            return new BioFormatsReaderWrapper(
                    args,
                    metadata,
                    classList,
                    options,
                    id
            );
        } catch (FormatException e) {
            throw new IOException(e);
        }
    }

    @Override
    public byte[][] getPixelValues(TileRequest tileRequest, int series) throws IOException {
        reader.setSeries(series);
        reader.setResolution(tileRequest.getLevel());
        int effectiveC = reader.getEffectiveSizeC();

        byte[][] bytes = new byte[effectiveC][];

        try {
            for (int channel = 0; channel < effectiveC; channel++) {
                bytes[channel] = reader.openBytes(
                        reader.getIndex(tileRequest.getZ(), channel, tileRequest.getT()),
                        tileRequest.getTileX(),
                        tileRequest.getTileY(),
                        tileRequest.getTileWidth(),
                        tileRequest.getTileHeight()
                );
            }
        } catch (FormatException e) {
            throw new IOException(e);
        }

        return bytes;
    }

    @Override
    public ImageInfo getPixelValues(int series) throws IOException {
        int previousSeries = reader.getSeries();

        try {
            reader.setSeries(series);
            int nResolutions = reader.getResolutionCount();
            if (nResolutions > 0) {
                reader.setResolution(0);
            }

            //TODO: Handle color transforms here, or in the display of labels/macro images - in case this isn't RGB
            return new ImageInfo(
                    reader.openBytes(reader.getIndex(0, 0, 0)),
                    reader.getSizeX(),
                    reader.getSizeY(),
                    reader.isInterleaved()
            );
        } catch (FormatException e) {
            throw new IOException(e);
        } finally {
            reader.setSeries(previousSeries);
        }
    }

    @Override
    public ByteOrder getByteOrder() {
        return byteOrder;
    }

    @Override
    public PixelType getPixelType() {
        return pixelType;
    }

    @Override
    public boolean isNormalized() {
        return reader.isNormalized();
    }

    @Override
    public boolean isInterleaved() {
        return reader.isInterleaved();
    }

    @Override
    public boolean isIndexed() {
        return reader.isIndexed();
    }

    @Override
    public boolean isRGB() {
        return reader.isRGB();
    }

    @Override
    public int getEffectiveNumberOfChannels() {
        return reader.getEffectiveSizeC();
    }

    @Override
    public int getRGBChannelCount() {
        return reader.getRGBChannelCount();
    }

    @Override
    public int getChannelCount(int imageIndex) {
        return metadata.getChannelCount(imageIndex);
    }

    @Override
    public int getChannelSamplesPerPixel(int imageIndex, int channelIndex) {
        return metadata.getChannelSamplesPerPixel(imageIndex, channelIndex).getValue();
    }

    @Override
    public Optional<byte[][]> get8BitLookupTable() throws IOException {
        try {
            byte[][] table = reader.get8BitLookupTable();
            if (table == null) {
                throw new IllegalStateException("This is not an indexed 8-bit image");
            }
            return Optional.of(table);
        } catch (FormatException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Optional<short[][]> get16BitLookupTable() throws IOException {
        try {
            short[][] table = reader.get16BitLookupTable();
            if (table == null) {
                throw new IllegalStateException("This is not an indexed 16-bit image");
            }
            return Optional.of(table);
        } catch (FormatException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() throws Exception {
        reader.close(false);
    }

    /**
     * @return the underlying reader used by this wrapper
     */
    public IFormatReader getReader() {
        return reader;
    }

    private void setReaderOptions(BioFormatsImageServer.BioFormatsArgs args) {
        MetadataOptions metadataOptions = reader.getMetadataOptions();
        var readerOptions = args.readerOptions;
        if (!readerOptions.isEmpty() && metadataOptions instanceof DynamicMetadataOptions) {
            for (var option : readerOptions.entrySet()) {
                ((DynamicMetadataOptions)metadataOptions).set(option.getKey(), option.getValue());
            }
        }
    }

    private void setMetadata(boolean isFirstReader) {
        if (isFirstReader) {
            reader.setMetadataStore(metadata);
        } else {
            reader.setMetadataStore(new DummyMetadata());
            reader.setOriginalMetadataPopulated(false);
        }
    }

    private static File getTempMemoDir() throws IOException {
        if (dirMemoTemp == null) {
            synchronized (BioFormatsReaderWrapper.class) {
                if (dirMemoTemp == null) {
                    Path path = Files.createTempDirectory("qupath-memo-");
                    dirMemoTemp = path.toFile();
                    Runtime.getRuntime().addShutdownHook(new Thread(BioFormatsReaderWrapper::deleteTempMemoFiles));
                    logger.warn("Temp memoization directory created at {}", dirMemoTemp);
                    logger.warn("If you want to avoid this warning, either specify a memoization directory in the preferences or turn off memoization by setting the time to < 0");
                }
            }
        }
        return dirMemoTemp;
    }

    /**
     * Delete any memoization files registered as being temporary, and also the
     * temporary memoization directory (if it exists).
     * Note that this acts both recursively and rather conservatively, stopping if a file is
     * encountered that is not expected.
     */
    private static void deleteTempMemoFiles() {
        for (File f : tempMemoFiles) {
            // Be extra-careful not to delete too much...
            if (!f.exists())
                continue;
            if (!f.isFile() || !f.getName().endsWith(".bfmemo")) {
                logger.warn("Unexpected memoization file, will not delete {}", f.getAbsolutePath());
                return;
            }
            if (f.delete())
                logger.debug("Deleted temp memoization file {}", f.getAbsolutePath());
            else
                logger.warn("Could not delete temp memoization file {}", f.getAbsolutePath());
        }
        if (dirMemoTemp == null)
            return;
        deleteEmptyDirectories(dirMemoTemp);
    }

    /**
     * Delete a directory and all sub-directories, assuming each contains only empty directories.
     * This is applied recursively, stopping at the first failure (i.e. any directory containing files).
     * @return true if the directory could be deleted, false otherwise
     */
    private static boolean deleteEmptyDirectories(File dir) {
        if (!dir.isDirectory())
            return false;
        int nFiles = 0;
        var files = dir.listFiles();
        if (files == null) {
            logger.debug("Unable to list files for {}", dir);
            return false;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (!deleteEmptyDirectories(f))
                    return false;
            } else if (f.isFile())
                nFiles++;
        }
        if (nFiles == 0) {
            if (dir.delete()) {
                logger.debug("Deleting empty memoization directory {}", dir.getAbsolutePath());
                return true;
            } else {
                logger.warn("Could not delete temp memoization directory {}", dir.getAbsolutePath());
                return false;
            }
        } else {
            logger.warn("Temp memoization directory contains files, will not delete {}", dir.getAbsolutePath());
            return false;
        }
    }
}
