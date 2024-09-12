package qupath.lib.images.writers.ome.zarr;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.AbstractImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.PixelType;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferDouble;
import java.awt.image.WritableRaster;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class TestOMEZarrWriter2 {

    boolean deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directoryToBeDeleted.delete();
    }

    @Test
    void Check_Error_When_Extension_Incorrect() throws Exception {
        String extension = ".wrong.extension";
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image" + extension).toString();
        SampleImageServer sampleImageServer = new SampleImageServer();

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
        );

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_Full_Image_Pixels() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        int level = 0;
        int z = 2;
        int t = 1;
        BufferedImage expectedImage = sampleImageServer.readRegion(
                sampleImageServer.getDownsampleForResolution(level),
                0,
                0,
                sampleImageServer.getWidth(),
                sampleImageServer.getHeight(),
                z,
                t
        );

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .build();
        writer.writeImage();
        writer.close();

        BufferedImage image;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            image = server.readRegion(
                    server.getDownsampleForResolution(level),
                    0,
                    0,
                    server.getWidth(),
                    server.getHeight(),
                    z,
                    t
            );
        }
        assertDoubleBufferedImagesEqual(expectedImage, image);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_Downsampled_Image_Pixels() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        int level = 1;
        int z = 2;
        int t = 1;
        BufferedImage expectedImage = sampleImageServer.readRegion(
                sampleImageServer.getDownsampleForResolution(level),
                0,
                0,
                sampleImageServer.getWidth(),
                sampleImageServer.getHeight(),
                z,
                t
        );

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .build();
        writer.writeImage();
        writer.close();

        BufferedImage image;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            image = server.readRegion(
                    server.getDownsampleForResolution(level),
                    0,
                    0,
                    server.getWidth(),
                    server.getHeight(),
                    z,
                    t
            );
        }
        assertDoubleBufferedImagesEqual(expectedImage, image);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_Downsamples_When_Not_Specified() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        double[] expectedDownsamples = sampleImageServer.getPreferredDownsamples();

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .setDownsamples()
                .build();
        writer.writeImage();
        writer.close();

        double[] downsamples;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            downsamples = server.getPreferredDownsamples();
        }
        Assertions.assertArrayEquals(expectedDownsamples, downsamples);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_Downsamples_When_Specified() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        double[] expectedDownsamples = new double[] {1, 2, 4};

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .setDownsamples(expectedDownsamples)
                .build();
        writer.writeImage();
        writer.close();

        double[] downsamples;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            downsamples = server.getPreferredDownsamples();
        }
        Assertions.assertArrayEquals(expectedDownsamples, downsamples);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_Default_Tile_Width() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        int expectedTileWidth = sampleImageServer.getMetadata().getPreferredTileWidth();

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .setTileWidth(-1)
                .build();
        writer.writeImage();
        writer.close();

        int tileWidth;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            tileWidth = server.getMetadata().getPreferredTileWidth();
        }
        Assertions.assertEquals(expectedTileWidth, tileWidth);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_Custom_Tile_Width() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        int expectedTileWidth = 64;

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .setTileWidth(expectedTileWidth)
                .build();
        writer.writeImage();
        writer.close();

        int tileWidth;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            tileWidth = server.getMetadata().getPreferredTileWidth();
        }
        Assertions.assertEquals(expectedTileWidth, tileWidth);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_Default_Tile_Height() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        int expectedTileHeight = sampleImageServer.getMetadata().getPreferredTileHeight();

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .setTileHeight(-1)
                .build();
        writer.writeImage();
        writer.close();

        int tileHeight;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            tileHeight = server.getMetadata().getPreferredTileHeight();
        }
        Assertions.assertEquals(expectedTileHeight, tileHeight);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_Custom_Tile_Height() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        int expectedTileHeight = 64;

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .setTileHeight(expectedTileHeight)
                .build();
        writer.writeImage();
        writer.close();

        int tileHeight;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            tileHeight = server.getMetadata().getPreferredTileHeight();
        }
        Assertions.assertEquals(expectedTileHeight, tileHeight);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_Bounding_Box() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        int z = 2;
        int t = 1;
        ImageRegion boundingBox = ImageRegion.createInstance(5, 5, 20, 25, z, t);
        BufferedImage expectedImage = sampleImageServer.readRegion(RegionRequest.createInstance(sampleImageServer.getPath(), 1, boundingBox));

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .setBoundingBox(boundingBox)
                .build();
        writer.writeImage();
        writer.close();

        BufferedImage image;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            image = server.readRegion(1, 0, 0, server.getWidth(), server.getHeight(), z, t);
        }
        assertDoubleBufferedImagesEqual(expectedImage, image);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_Z_Sliced_Image_Number_Of_Z_Stacks() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        int zStart = 1;
        int zEnd = 3;
        int expectedNumberOfZStacks = zEnd - zStart;

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .setZSlices(zStart, zEnd)
                .build();
        writer.writeImage();
        writer.close();

        int numberOfZStacks;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            numberOfZStacks = server.nZSlices();
        }
        Assertions.assertEquals(expectedNumberOfZStacks, numberOfZStacks);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_Z_Sliced_Image_Pixels() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        int zStart = 1;
        int zEnd = 3;
        int z = 1;
        int t = 1;
        BufferedImage expectedImage = sampleImageServer.readRegion(RegionRequest.createInstance(
                sampleImageServer.getPath(),
                1,
                0,
                0,
                sampleImageServer.getWidth(),
                sampleImageServer.getHeight(),
                z,
                t
        ));

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .setZSlices(zStart, zEnd)
                .build();
        writer.writeImage();
        writer.close();

        BufferedImage image;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            image = server.readRegion(1, 0, 0, server.getWidth(), server.getHeight(), z - zStart, t);
        }
        assertDoubleBufferedImagesEqual(expectedImage, image);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_T_Sliced_Image_Number_Of_Timepoints() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        int tStart = 1;
        int tEnd = 2;
        int expectedNumberOfTimepoints = tEnd - tStart;

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .setTimepoints(tStart, tEnd)
                .build();
        writer.writeImage();
        writer.close();

        int numberOfTimepoints;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            numberOfTimepoints = server.nTimepoints();
        }
        Assertions.assertEquals(expectedNumberOfTimepoints, numberOfTimepoints);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    @Test
    void Check_T_Sliced_Image_Pixels() throws Exception {
        Path path = Files.createTempDirectory(UUID.randomUUID().toString());
        String outputImagePath = Paths.get(path.toString(), "image.ome.zarr").toString();
        SampleImageServer sampleImageServer = new SampleImageServer();
        int tStart = 1;
        int tEnd = 2;
        int z = 1;
        int t = 1;
        BufferedImage expectedImage = sampleImageServer.readRegion(RegionRequest.createInstance(
                sampleImageServer.getPath(),
                1,
                0,
                0,
                sampleImageServer.getWidth(),
                sampleImageServer.getHeight(),
                z,
                t
        ));

        OMEZarrWriter writer = new OMEZarrWriter.Builder(sampleImageServer, outputImagePath)
                .setTimepoints(tStart, tEnd)
                .build();
        writer.writeImage();
        writer.close();

        BufferedImage image;
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(outputImagePath, BufferedImage.class)) {
            image = server.readRegion(1, 0, 0, server.getWidth(), server.getHeight(), z, t - tStart);
        }
        assertDoubleBufferedImagesEqual(expectedImage, image);

        sampleImageServer.close();
        deleteDirectory(path.toFile());
    }

    private static class SampleImageServer extends AbstractImageServer<BufferedImage> {

        private static final int IMAGE_WIDTH = 64;
        private static final int IMAGE_HEIGHT = 64;

        public SampleImageServer() {
            super(BufferedImage.class);
        }

        @Override
        protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
            return null;
        }

        @Override
        protected String createID() {
            return getClass().getName();
        }

        @Override
        public Collection<URI> getURIs() {
            return List.of();
        }

        @Override
        public String getServerType() {
            return "Sample server";
        }

        @Override
        public ImageServerMetadata getOriginalMetadata() {
            return new ImageServerMetadata.Builder()
                    .width(IMAGE_WIDTH)
                    .height(IMAGE_HEIGHT)
                    .sizeZ(3)
                    .sizeT(2)
                    .pixelType(PixelType.FLOAT64)
                    .preferredTileSize(32, 32)
                    .channels(List.of(
                            ImageChannel.getInstance("c1", 1),
                            ImageChannel.getInstance("c2", 2),
                            ImageChannel.getInstance("c3", 3),
                            ImageChannel.getInstance("c4", 4),
                            ImageChannel.getInstance("c5", 5)
                    ))
                    .name("name")
                    .levelsFromDownsamples(1, 2)
                    .build();
        }

        @Override
        public BufferedImage readRegion(RegionRequest request) {
            DataBuffer dataBuffer = createDataBuffer(request);

            return new BufferedImage(
                    ColorModelFactory.createColorModel(getMetadata().getPixelType(), getMetadata().getChannels()),
                    WritableRaster.createWritableRaster(
                            new BandedSampleModel(
                                    dataBuffer.getDataType(),
                                    (int) (request.getWidth() / request.getDownsample()),
                                    (int) (request.getHeight() / request.getDownsample()),
                                    nChannels()
                            ),
                            dataBuffer,
                            null
                    ),
                    false,
                    null
            );
        }

        private DataBuffer createDataBuffer(RegionRequest request) {
            double[][] array = new double[nChannels()][];

            for (int c = 0; c < array.length; c++) {
                array[c] = getPixels(request, c);
            }

            return new DataBufferDouble(array, (int) (request.getWidth() * request.getHeight() / 8 / (request.getDownsample() * request.getDownsample())));
        }

        private double[] getPixels(RegionRequest request, int channel) {
            int originX = (int) (request.getX() / request.getDownsample());
            int originY = (int) (request.getY() / request.getDownsample());
            int width = (int) (request.getWidth() / request.getDownsample());
            int height = (int) (request.getHeight() / request.getDownsample());
            double[] pixels = new double[width * height];

            for (int y=0; y<height; y++) {
                for (int x=0; x<width; x++) {
                    pixels[y*width + x] = getPixel(x + originX, y + originY, channel, request.getZ(), request.getT());
                }
            }

            return pixels;
        }

        private static double getPixel(int x, int y, int channel, int z, int t) {
            return z + t + channel + ((double) x / IMAGE_WIDTH + (double) y / IMAGE_HEIGHT) / 2;
        }
    }

    private void assertDoubleBufferedImagesEqual(BufferedImage expectedImage, BufferedImage actualImage) {
        Assertions.assertEquals(expectedImage.getWidth(), actualImage.getWidth());
        Assertions.assertEquals(expectedImage.getHeight(), actualImage.getHeight());

        double[] expectedPixels = new double[expectedImage.getSampleModel().getNumBands()];
        double[] actualPixels = new double[actualImage.getSampleModel().getNumBands()];
        for (int x = 0; x < expectedImage.getWidth(); x++) {
            for (int y = 0; y < expectedImage.getHeight(); y++) {
                Assertions.assertArrayEquals(
                        (double[]) expectedImage.getData().getDataElements(x, y, expectedPixels),
                        (double[]) actualImage.getData().getDataElements(x, y, actualPixels)
                );
            }
        }
    }
}
