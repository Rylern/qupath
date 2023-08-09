package qupath.lib.images.servers.bioformats;

import loci.formats.*;
import loci.formats.ome.OMEPyramidStore;

import java.util.Optional;

/**
 * <p>A {@link ReaderPool} to use with Bio-Format images.</p>
 * <p>It uses the {@link BioFormatReaderWrapper}.</p>
 */
class BioFormatReaderPool extends ReaderPool {
    private final BioFormatsServerOptions options;
    private final String id;
    private final BioFormatsImageServer.BioFormatsArgs args;
    private final OMEPyramidStore metadata;
    private ClassList<IFormatReader> classList;

    /**
     * Creates a new bio-format reader pool.
     *
     * @param options  options that customize the behavior of the pool (e.g. memoization, max number of reader)
     * @param id  a unique identifier for the image to be opened by this pool of readers
     * @param args  optional arguments
     */
    public BioFormatReaderPool(BioFormatsServerOptions options, String id, BioFormatsImageServer.BioFormatsArgs args) {
        this.options = options;
        this.id = id;
        this.args = args;
        this.metadata = (OMEPyramidStore) MetadataTools.createOMEXMLMetadata();
    }

    /**
     * <p>
     *     Get the the first bio-format reader wrapper created by this pool.
     *     It can be used to manually perform other operations such as fetching metadata.
     * </p>
     * <p>
     *     The reader of the returned reader wrapper is not thread safe, so it should be used
     *     with the {@code synchronized} keyword.
     * </p>
     *
     * @return the main reader wrapper used by this pool, or an empty Optional if its creation failed
     */
    public Optional<BioFormatReaderWrapper> getDefaultReaderWrapper() {
        return getMainReaderWrapper().map(readerWrapper -> (BioFormatReaderWrapper) readerWrapper);
    }

    @Override
    protected Optional<? extends ReaderWrapper> createReaderWrapper() {
        Optional<BioFormatReaderWrapper> reader;

        if (classList == null) {
            reader = BioFormatReaderWrapper.createFirstReader(
                    args,
                    metadata,
                    options,
                    id
            );
            reader.ifPresent(bioFormatReaderWrapper -> classList = unwrapClasslist(bioFormatReaderWrapper.getReader()));
        } else {
            reader = BioFormatReaderWrapper.createReader(
                    args,
                    metadata,
                    classList,
                    options,
                    id
            );
        }

        return reader;
    }

    @Override
    protected int getMaxNumberOfReaders() {
        return Math.max(1, options == null ? Runtime.getRuntime().availableProcessors() : options.getMaxReaders());
    }

    private static ClassList<IFormatReader> unwrapClasslist(IFormatReader reader) {
        while (reader instanceof loci.formats.ReaderWrapper)
            reader = ((loci.formats.ReaderWrapper)reader).getReader();
        var classlist = new ClassList<>(IFormatReader.class);
        classlist.addClass(reader.getClass());
        return classlist;
    }
}
