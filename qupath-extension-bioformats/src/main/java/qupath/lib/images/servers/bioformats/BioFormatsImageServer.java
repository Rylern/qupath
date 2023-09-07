/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2023 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.images.servers.bioformats;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import loci.formats.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ome.units.UNITS;
import ome.units.quantity.Length;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Unmatched;
import loci.formats.ome.OMEPyramidStore;
import loci.formats.ome.OMEXMLMetadata;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerMetadata.ImageResolutionLevel;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.bioformats.readers.BioFormatsReaderWrapper;
import qupath.lib.images.servers.bioformats.readers.ReaderPool;

/**
 * QuPath ImageServer that uses the Bio-Formats library to read image data.
 * <p>
 *     See <a href="http://www.openmicroscopy.org/site/products/bio-formats">bio-formats</a>.
 * <p>
 * <p>
 *     See also
 *     <a href="https://docs.openmicroscopy.org/bio-formats/6.5.1/developers/matlab-dev.html#improving-reading-performance">improving-reading-performance</a>
 * </p>
 *
 * @author Pete Bankhead
 *
 */
public class BioFormatsImageServer extends AbstractTileableImageServer {

	private static final Logger logger = LoggerFactory.getLogger(BioFormatsImageServer.class);

	/**
	 * Minimum tile size - smaller values will be ignored.
	 */
	private static final int MIN_TILE_SIZE = 32;

	/**
	 * Default tile size - when no other value is available.
	 */
	private static final int DEFAULT_TILE_SIZE = 512;

	/**
	 * Image names (in lower case) normally associated with 'extra' images, but probably not representing the main image in the file.
	 */
	private static final Collection<String> extraImageNames = Set.of(
			"overview", "label", "thumbnail", "macro", "macro image", "macro mask image", "label image", "overview image", "thumbnail image"
	);

	/**
	 * The original URI requested for this server.
	 */
	private final URI uri;

	/**
	 * Original metadata, populated when reading the file.
	 */
	private final ImageServerMetadata originalMetadata;

	/**
	 * Arguments passed to constructor.
	 */
	private final String[] args;

	/**
	 * File path if possible, or a URI otherwise.
	 */
	private String filePathOrUrl;

	/**
	 * A map linking an identifier (image name) to series number for 'full' images.
	 */
	private final Map<String, ServerBuilder<BufferedImage>> imageMap;

	/**
	 * A map linking an identifier (image name) to series number for additional images, e.g. thumbnails or macro images.
	 */
	private final Map<String, Integer> associatedImageMap;

	/**
	 * Numeric identifier for the image (there might be more than one in the file)
	 */
	private final int series;

	/**
	 * Format for the current reader.
	 */
	private final String format;

	/**
	 * Pool of readers for use with this server.
	 */
	private final ReaderPool readerPool;

	/**
	 * ColorModel to use with all BufferedImage requests.
	 */
	private final ColorModel colorModel;

	private ClassList<IFormatReader> classList;

	/**
	 * Create an ImageServer using the Bio-Formats library.
	 * <p>
	 *     This requires an <i>absolute</i> URI, where an integer fragment can be used to define the series number.
	 * </p>
	 *
	 * @param uri for the image that should be opened; this might include a sub-image as a query or fragment.
	 * @param args optional arguments
	 */
	public static Optional<BioFormatsImageServer> create(URI uri, String... args) {
		try {
			return Optional.of(new BioFormatsImageServer(uri, BioFormatsServerOptions.getInstance(), args));
		} catch (Exception e) {
			logger.error("Error when creating BioFormatsImageServer", e);
			return Optional.empty();
		}
	}

	private BioFormatsImageServer(URI uri, final BioFormatsServerOptions options, String... args) throws IOException, URISyntaxException {
		long startTime = System.currentTimeMillis();

		// See if there is a series name embedded in the path (temporarily the way things were done in v0.2.0-m1 and v0.2.0-m2)
		// Add it to the args if so
		if (args.length == 0) {
			if (uri.getFragment() != null) {
				args = new String[] {"--series", uri.getFragment()};
			} else if (uri.getQuery() != null) {
				// Queries supported name=image-name or series=series-number... only one or the other!
				String query = uri.getQuery();
				String seriesQuery = "series=";
				String nameQuery = "name=";
				if (query.startsWith(seriesQuery)) {
					args = new String[] {"--series", query.substring(seriesQuery.length())};
				} else if (query.startsWith(nameQuery)) {
					args = new String[] {"--name", query.substring(nameQuery.length())};
				}
			}
			uri = new URI(uri.getScheme(), uri.getHost(), uri.getPath(), null);
		}
		this.uri = uri;

		// Try to get a local file path, but accept something else (since Bio-Formats handles other URIs)
		try {
			var path = GeneralTools.toPath(uri);
			if (path != null) {
				filePathOrUrl = path.toString();
			}
		} catch (Exception e) {
			logger.error(e.getLocalizedMessage(), e);
		} finally {
			if (filePathOrUrl == null) {
				logger.debug("Using URI as file path: {}", uri);
				filePathOrUrl = uri.toString();
			}
		}

		// Parse the arguments
		BioFormatsArgs bfArgs = BioFormatsArgs.parse(args);

		var metadata = (OMEPyramidStore) MetadataTools.createOMEXMLMetadata();
		readerPool = new ReaderPool(
				Math.max(1, options == null ? Runtime.getRuntime().availableProcessors() : options.getMaxReaders()),
				() -> {
					logger.debug("Creating Bio-Formats reader wrapper");

					BioFormatsReaderWrapper reader;
					if (classList == null) {
						reader = BioFormatsReaderWrapper.createFirstReader(
								bfArgs,
								metadata,
								options,
								filePathOrUrl
						);
						classList = unwrapClasslist(reader.getReader());
					} else {
						reader = BioFormatsReaderWrapper.createReader(
								bfArgs,
								metadata,
								classList,
								options,
								filePathOrUrl
						);
					}

					return reader;
				}
		);

		// Try to parse args, extracting the series if present
		int seriesIndex = bfArgs.series;
		String requestedSeriesName = bfArgs.seriesName;
		if (requestedSeriesName.isBlank())
			requestedSeriesName = null;

		// Create a reader & extract the metadata
		BioFormatsReaderWrapper readerWrapper;
		try {
			readerWrapper = (BioFormatsReaderWrapper) readerPool.getDedicatedReaderWrapper();
		} catch (InterruptedException e) {
			throw new RuntimeException("Could not retrieve main reader", e);
		}
		IFormatReader reader = readerWrapper.getReader();
		var meta = (OMEPyramidStore)reader.getMetadataStore();

		// Populate the image server list if we have more than one image
		int largestSeries = -1;
		int firstSeries = -1;
		long mostPixels = -1L;
		long firstPixels = -1L;

		// If we have more than one series, we need to construct maps of 'analyzable' & associated images
		int nImages = meta.getImageCount();
		imageMap = new LinkedHashMap<>(nImages);
		associatedImageMap = new LinkedHashMap<>(nImages);

		// Loop through series to find out whether we have multiresolution images, or associated images (e.g. thumbnails)
		for (int s = 0; s < nImages; s++) {
			String name = "Series " + s;
			String originalImageName = getImageName(meta, s);
			if (originalImageName == null)
				originalImageName = "";

			String imageName = originalImageName;
			try {
				if (!imageName.isEmpty())
					name += " (" + imageName + ")";

				// Set this to be the series, if necessary
				long sizeX = meta.getPixelsSizeX(s).getNumberValue().longValue();
				long sizeY = meta.getPixelsSizeY(s).getNumberValue().longValue();
				long sizeC = meta.getPixelsSizeC(s).getNumberValue().longValue();
				long sizeZ = meta.getPixelsSizeZ(s).getNumberValue().longValue();
				long sizeT = meta.getPixelsSizeT(s).getNumberValue().longValue();

				// Check the resolutions
				//						int nResolutions = meta.getResolutionCount(s);
				//						for (int r = 1; r < nResolutions; r++) {
				//							int sizeXR = meta.getResolutionSizeX(s, r).getValue();
				//							int sizeYR = meta.getResolutionSizeY(s, r).getValue();
				//							if (sizeXR <= 0 || sizeYR <= 0 || sizeXR > sizeX || sizeYR > sizeY)
				//								throw new IllegalArgumentException("Resolution " + r + " size " + sizeXR + " x " + sizeYR + " invalid!");
				//						}
				// It seems we can't get the resolutions from the metadata object... instead we need to set the series of the reader
				reader.setSeries(s);
				assert reader.getSizeX() == sizeX;
				assert reader.getSizeY() == sizeY;
				int nResolutions = reader.getResolutionCount();
				for (int r = 1; r < nResolutions; r++) {
					reader.setResolution(r);
					int sizeXR = reader.getSizeX();
					int sizeYR = reader.getSizeY();
					if (sizeXR <= 0 || sizeYR <= 0 || sizeXR > sizeX || sizeYR > sizeY)
						throw new IllegalArgumentException("Resolution " + r + " size " + sizeXR + " x " + sizeYR + " invalid!");
				}

				// If we got this far, we have an image we can add
				if (reader.getResolutionCount() == 1 && (
						extraImageNames.contains(originalImageName.toLowerCase()) || extraImageNames.contains(name.toLowerCase().trim()))) {
					logger.debug("Adding associated image {} (thumbnail={})", name, reader.isThumbnailSeries());
					associatedImageMap.put(name, s);
				} else {
					if (imageMap.containsKey(name))
						logger.warn("Duplicate image called {} - only the first will be used", name);
					else {
						if (firstSeries < 0) {
							firstSeries = s;
							firstPixels = sizeX * sizeY * sizeZ * sizeT;
						}
						imageMap.put(name, DefaultImageServerBuilder.createInstance(
								BioFormatsServerBuilder.class, null, uri,
								bfArgs.backToArgs(s)
						));
					}
				}

				if (seriesIndex < 0) {
					if (requestedSeriesName == null) {
						long nPixels = sizeX * sizeY * sizeZ * sizeT;
						if (nPixels > mostPixels) {
							largestSeries = s;
							mostPixels = nPixels;
						}
					} else if (requestedSeriesName.equals(name) || requestedSeriesName.equals(getImageName(meta, s)) || requestedSeriesName.contentEquals(meta.getImageName(s))) {
						seriesIndex = s;
					}
				}
				logger.debug("Found image '{}', size: {} x {} x {} x {} x {} (xyczt)", imageName, sizeX, sizeY, sizeC, sizeZ, sizeT);
			} catch (Exception e) {
				// We don't want to log this prominently if we're requesting a different series anyway
				if ((seriesIndex < 0 || seriesIndex == s) && (requestedSeriesName == null || requestedSeriesName.equals(imageName)))
					logger.warn("Error attempting to read series " + s + " (" + imageName + ") - will be skipped", e);
				else
					logger.trace("Error attempting to read series " + s + " (" + imageName + ") - will be skipped", e);
			}
		}

		// If we have just one image in the image list, then reset to none - we can't switch
		if (imageMap.size() == 1 && seriesIndex < 0) {
			seriesIndex = firstSeries;
//					imageMap.clear();
		} else if (imageMap.size() > 1) {
			// Set default series index, if we need to
			if (seriesIndex < 0) {
				// Choose the first series unless it is substantially smaller than the largest series (e.g. it's a label or macro image)
				if (mostPixels > firstPixels * 4L)
					seriesIndex = largestSeries; // imageMap.values().iterator().next();
				else
					seriesIndex = firstSeries;
			}
			// If we have more than one image, ensure that we have the image name correctly encoded in the path
			uri = new URI(uri.getScheme(), uri.getHost(), uri.getPath(), Integer.toString(seriesIndex));
		}

		if (seriesIndex < 0)
			throw new IOException("Unable to find any valid images within " + uri);

		// Store the series we are actually using
		this.series = seriesIndex;
		reader.setSeries(series);

		// Get the format in case we need it
		format = reader.getFormat();
		logger.debug("Reading format: {}", format);

		// Try getting the magnification
		double magnification = Double.NaN;
		try {
			String objectiveID = meta.getObjectiveSettingsID(series);
			int objectiveIndex = -1;
			int instrumentIndex = -1;
			int nInstruments = meta.getInstrumentCount();
			for (int i = 0; i < nInstruments; i++) {
				int nObjectives = meta.getObjectiveCount(i);
				for (int o = 0; 0 < nObjectives; o++) {
					if (objectiveID.equals(meta.getObjectiveID(i, o))) {
						instrumentIndex = i;
						objectiveIndex = o;
						break;
					}
				}
			}
			if (instrumentIndex < 0) {
				logger.warn("Cannot find objective for ref {}", objectiveID);
			} else {
				Double magnificationObject = meta.getObjectiveNominalMagnification(instrumentIndex, objectiveIndex);
				if (magnificationObject == null) {
					logger.warn("Nominal objective magnification missing for {}:{}", instrumentIndex, objectiveIndex);
				} else
					magnification = magnificationObject;
			}
		} catch (Exception e) {
			logger.debug("Unable to parse magnification: {}", e.getLocalizedMessage());
		}

		// Get the dimensions for the requested series
		// The first resolution is the highest, i.e. the largest image
		int width = reader.getSizeX();
		int height = reader.getSizeY();
		int tileWidth = reader.getOptimalTileWidth();
		int tileHeight = reader.getOptimalTileHeight();
		int nChannels = reader.getSizeC();

		// Make sure tile sizes are within range
		if (tileWidth != width)
			tileWidth = getDefaultTileLength(tileWidth, width);
		if (tileHeight != height)
			tileHeight = getDefaultTileLength(tileHeight, height);

		// Prepared to set channel colors
		List<ImageChannel> channels = new ArrayList<>();

		int nZSlices = reader.getSizeZ();
//			// Workaround bug whereby VSI channels can also be replicated as z-slices
//			if (options.requestChannelZCorrectionVSI() && nZSlices == nChannels && nChannels > 1 && "CellSens VSI".equals(format)) {
//				doChannelZCorrectionVSI = true;
//				nZSlices = 1;
//			}
		int nTimepoints = reader.getSizeT();

		PixelType pixelType;
		switch (reader.getPixelType()) {
			case FormatTools.BIT:
				logger.warn("Pixel type is BIT! This is not currently supported by QuPath.");
				pixelType = PixelType.UINT8;
				break;
			case FormatTools.INT8:
				logger.warn("Pixel type is INT8! This is not currently supported by QuPath.");
				pixelType = PixelType.INT8;
				break;
			case FormatTools.UINT8:
				pixelType = PixelType.UINT8;
				break;
			case FormatTools.INT16:
				pixelType = PixelType.INT16;
				break;
			case FormatTools.UINT16:
				pixelType = PixelType.UINT16;
				break;
			case FormatTools.INT32:
				pixelType = PixelType.INT32;
				break;
			case FormatTools.UINT32:
				logger.warn("Pixel type is UINT32! This is not currently supported by QuPath.");
				pixelType = PixelType.UINT32;
				break;
			case FormatTools.FLOAT:
				pixelType = PixelType.FLOAT32;
				break;
			case FormatTools.DOUBLE:
				pixelType = PixelType.FLOAT64;
				break;
			default:
				throw new IllegalArgumentException("Unsupported pixel type " + reader.getPixelType());
		}

		// Determine min/max values if we can
		int bpp = reader.getBitsPerPixel();
		Number minValue = null;
		Number maxValue = null;
		if (bpp < pixelType.getBitsPerPixel()) {
			if (pixelType.isSignedInteger()) {
				minValue = -(int)Math.pow(2, bpp-1);
				maxValue = (int)(Math.pow(2, bpp-1) - 1);
			} else if (pixelType.isUnsignedInteger()) {
				maxValue = (int)(Math.pow(2, bpp) - 1);
			}
		}

		boolean isRGB = reader.isRGB() && pixelType == PixelType.UINT8;
		// Remove alpha channel
		if (isRGB && nChannels == 4) {
			logger.warn("Removing alpha channel");
			nChannels = 3;
		} else if (nChannels != 3)
			isRGB = false;

		// Try to read the default display colors for each channel from the file
		if (isRGB) {
			channels.addAll(ImageChannel.getDefaultRGBChannels());
			colorModel = ColorModelFactory.createColorModel(pixelType, channels);
		}
		else {
			// Get channel colors and names
			var tempColors = new ArrayList<ome.xml.model.primitives.Color>(nChannels);
			var tempNames = new ArrayList<String>(nChannels);
			// Be prepared to use default channels if something goes wrong
			try {
				int metaChannelCount = meta.getChannelCount(series);
				// Handle the easy case where the number of channels matches our expectations
				if (metaChannelCount == nChannels) {
					for (int c = 0; c < nChannels; c++) {
						try {
							// try/catch from old code, before we explicitly checked channel count
							// No exception should occur now
							var channelName = meta.getChannelName(series, c);
							var color = meta.getChannelColor(series, c);
							tempNames.add(channelName);
							tempColors.add(color);
						} catch (Exception e) {
							logger.warn("Unable to parse name or color for channel {}", c);
							logger.debug("Unable to parse color", e);
						}
					}
				} else {
					// Handle the harder case, where we have a different number of channels
					// I've seen this with a polarized light CZI image, with a channel count of 2
					// but in which each of these had 3 samples (resulting in a total of 6 channels)
					logger.debug("Attempting to parse {} channels with metadata channel count {}", nChannels, metaChannelCount);
					int ind = 0;
					for (int cInd = 0; cInd < metaChannelCount; cInd++) {
						int nSamples = meta.getChannelSamplesPerPixel(series, cInd).getValue();
						var baseChannelName = meta.getChannelName(series, cInd);
						if (baseChannelName != null && baseChannelName.isBlank())
							baseChannelName = null;
						// I *expect* this to be null for interleaved channels, in which case it will be filled in later
						var color = meta.getChannelColor(series, cInd);
						for (int sampleInd = 0; sampleInd < nSamples; sampleInd++) {
							String channelName;
							if (baseChannelName == null)
								channelName = "Channel " + (ind + 1);
							else
								channelName = baseChannelName.strip() + " " + (sampleInd + 1);

							tempNames.add(channelName);
							tempColors.add(color);

							ind++;
						}
					}
				}
			} catch (Exception e) {
				logger.warn("Exception parsing channels " + e.getLocalizedMessage(), e);
			}
			if (nChannels != tempNames.size() || tempNames.size() != tempColors.size()) {
				logger.warn("The channel names and colors read from the metadata don't match the expected number of channels!");
				logger.warn("Be very cautious working with channels, since the names and colors may be misaligned, incorrect or default values.");
				long nNames = tempNames.stream().filter(n -> n != null && !n.isBlank()).count();
				long nColors = tempColors.stream().filter(Objects::nonNull).count();
				logger.warn("(I expected {} channels, but found {} names and {} colors)", nChannels, nNames, nColors);
				// Could reset them, but may help to use what we can
//					tempNames.clear();
//					tempColors.clear();
			}


			// Now loop through whatever we could parse and add QuPath ImageChannel objects
			for (int c = 0; c < nChannels; c++) {
				String channelName = c < tempNames.size() ? tempNames.get(c) : null;
				var color = c < tempColors.size() ? tempColors.get(c) : null;
				Integer channelColor = null;
				if (color != null)
					channelColor = ColorTools.packARGB(color.getAlpha(), color.getRed(), color.getGreen(), color.getBlue());
				else {
					// Select next available default color, or white (for grayscale) if only one channel
					if (nChannels == 1)
						channelColor = ColorTools.packRGB(255, 255, 255);
					else
						channelColor = ImageChannel.getDefaultChannelColor(c);
				}
				if (channelName == null || channelName.isBlank())
					channelName = "Channel " + (c + 1);
				channels.add(ImageChannel.getInstance(channelName, channelColor));
			}
			assert nChannels == channels.size();
			// Update RGB status if needed - sometimes we might really have an RGB image, but the Bio-Formats flag doesn't show this -
			// and we want to take advantage of the optimizations where we can
			if (nChannels == 3 &&
					pixelType == PixelType.UINT8 &&
					channels.equals(ImageChannel.getDefaultRGBChannels())
			) {
				isRGB = true;
				colorModel = ColorModel.getRGBdefault();
			} else {
				colorModel = ColorModelFactory.createColorModel(pixelType, channels);
			}
		}

		// Try parsing pixel sizes in micrometers
		double[] timepoints;
		double pixelWidth, pixelHeight;
		double zSpacing = Double.NaN;
		TimeUnit timeUnit = null;
		try {
			Length xSize = meta.getPixelsPhysicalSizeX(series);
			Length ySize = meta.getPixelsPhysicalSizeY(series);
			if (xSize != null && ySize != null) {
				pixelWidth = xSize.value(UNITS.MICROMETER).doubleValue();
				pixelHeight = ySize.value(UNITS.MICROMETER).doubleValue();
			} else {
				pixelWidth = Double.NaN;
				pixelHeight = Double.NaN;
			}
			// If we have multiple z-slices, parse the spacing
			if (nZSlices > 1) {
				Length zSize = meta.getPixelsPhysicalSizeZ(series);
				if (zSize != null)
					zSpacing = zSize.value(UNITS.MICROMETER).doubleValue();
				else
					zSpacing = Double.NaN;
			}
			// TODO: Check the Bioformats TimeStamps
			if (nTimepoints > 1) {
				logger.warn("Time stamps read from Bioformats have not been fully verified & should not be relied upon");
				// Here, we don't try to separate timings by z-slice & channel...
				int lastTimepoint = -1;
				int count = 0;
				timepoints = new double[nTimepoints];
				logger.debug("Plane count: " + meta.getPlaneCount(series));
				for (int plane = 0; plane < meta.getPlaneCount(series); plane++) {
					int timePoint = meta.getPlaneTheT(series, plane).getValue();
					logger.debug("Checking " + timePoint);
					if (timePoint != lastTimepoint) {
						timepoints[count] = meta.getPlaneDeltaT(series, plane).value(UNITS.SECOND).doubleValue();
						logger.debug(String.format("Timepoint %d: %.3f seconds", count, timepoints[count]));
						lastTimepoint = timePoint;
						count++;
					}
				}
				timeUnit = TimeUnit.SECONDS;
			} else {
				timepoints = new double[0];
			}
		} catch (Exception e) {
			logger.error("Error parsing metadata", e);
			pixelWidth = Double.NaN;
			pixelHeight = Double.NaN;
			zSpacing = Double.NaN;
			timepoints = null;
			timeUnit = null;
		}

		// Loop through the series & determine downsamples
		int nResolutions = reader.getResolutionCount();
		var resolutionBuilder = new ImageResolutionLevel.Builder(width, height)
				.addFullResolutionLevel();

		// I have seen czi files where the resolutions are not read correctly & this results in an IndexOutOfBoundsException
		for (int i = 1; i < nResolutions; i++) {
			reader.setResolution(i);
			try {
				int w = reader.getSizeX();
				int h = reader.getSizeY();
				if (w <= 0 || h <= 0) {
					logger.warn("Invalid resolution size {} x {}! Will skip this level, but something seems wrong...", w, h);
					continue;
				}
				// In some VSI images, the calculated downsamples for width & height can be wildly discordant,
				// and we are better off using defaults
				if ("CellSens VSI".equals(format)) {
					double downsampleX = (double)width / w;
					double downsampleY = (double)height / h;
					double downsample = Math.pow(2, i);
					if (!GeneralTools.almostTheSame(downsampleX, downsampleY, 0.01)) {
						logger.warn("Non-matching downsamples calculated for level {} ({} and {}); will use {} instead", i, downsampleX, downsampleY, downsample);
						resolutionBuilder.addLevel(downsample, w, h);
						continue;
					}
				}
				resolutionBuilder.addLevel(w, h);
			} catch (Exception e) {
				logger.warn("Error attempting to extract resolution " + i + " for " + getImageName(meta, series), e);
				break;
			}
		}

		// Generate a suitable name for this image
		String imageName = getFile().getName();
		String shortName = getImageName(meta, seriesIndex);
		if (shortName == null || shortName.isBlank()) {
			if (imageMap.size() > 1)
				imageName = imageName + " - Series " + seriesIndex;
		} else if (!imageName.equals(shortName))
			imageName = imageName + " - " + shortName;

		this.args = args;

		// Build resolutions
		var resolutions = resolutionBuilder.build();
		// Unused code to check if resolutions seem to be correct
//			var iter = resolutions.iterator();
//			int r = 0;
//			while (iter.hasNext()) {
//				var resolution = iter.next();
//				double widthDifference = Math.abs(resolution.getWidth() - width/resolution.getDownsample());
//				double heightDifference = Math.abs(resolution.getHeight() - height/resolution.getDownsample());
//				if (widthDifference > Math.max(2.0, resolution.getWidth()*0.01) || heightDifference > Math.max(2.0, resolution.getHeight()*0.01)) {
//					logger.warn("Aspect ratio of resolution level {} differs from", r);
//					iter.remove();
//					while (iter.hasNext()) {
//						iter.next();
//						iter.remove();
//					}
//				}
//				r++;
//			}

		// Set metadata
		String path = createID();
		ImageServerMetadata.Builder builder = new ImageServerMetadata.Builder(
				getClass(), path, width, height).
//					args(args).
	minValue(minValue).
				maxValue(maxValue).
				name(imageName).
				channels(channels).
				sizeZ(nZSlices).
				sizeT(nTimepoints).
				levels(resolutions).
				pixelType(pixelType).
				rgb(isRGB);

		if (Double.isFinite(magnification))
			builder = builder.magnification(magnification);

		if (timeUnit != null)
			builder = builder.timepoints(timeUnit, timepoints);

		if (Double.isFinite(pixelWidth + pixelHeight))
			builder = builder.pixelSizeMicrons(pixelWidth, pixelHeight);

		if (Double.isFinite(zSpacing))
			builder = builder.zSpacingMicrons(zSpacing);

		// Check the tile size if it is reasonable
		if ((long)tileWidth * (long)tileHeight * (long)nChannels * (bpp/8) >= Integer.MAX_VALUE) {
			builder.preferredTileSize(Math.min(DEFAULT_TILE_SIZE, width), Math.min(DEFAULT_TILE_SIZE, height));
		} else
			builder.preferredTileSize(tileWidth, tileHeight);

		originalMetadata = builder.build();

		// Bioformats can use ImageIO for JPEG decoding, and permitting the disk-based cache can slow it down... so here we turn it off
		// TODO: Document - or improve - the setting of ImageIO disk cache
		ImageIO.setUseCache(false);

		long endTime = System.currentTimeMillis();
		logger.debug(String.format("Initialization time: %d ms", endTime-startTime));
	}


	/**
	 * Get a sensible default tile size for a specified dimension.
	 * @param tileLength tile width or height
	 * @param imageLength corresponding image width or height
	 * @return a sensible tile length, bounded by the image width or height
	 */
	static int getDefaultTileLength(int tileLength, int imageLength) {
		if (tileLength <= 0) {
			tileLength = DEFAULT_TILE_SIZE;
		} else if (tileLength < MIN_TILE_SIZE) {
			tileLength = (int)Math.ceil((double)MIN_TILE_SIZE / tileLength) * tileLength;
		}
		return Math.min(tileLength, imageLength);
	}



	/**
	 * Get the image name for a series, making sure to remove any trailing null terminators.
	 * <p>
	 * See https://github.com/qupath/qupath/issues/573
	 * @param series
	 * @return
	 */
	private String getImageName(OMEXMLMetadata meta, int series) {
		String name = meta.getImageName(series);
		if (name == null)
			return null;
		while (name.endsWith("\0"))
			name = name.substring(0, name.length()-1);
		return name;
	}

	/**
	 * Get the format String, as returned by Bio-Formats {@code IFormatReader.getFormat()}.
	 * @return
	 */
	public String getFormat() {
		return format;
	}

	@Override
	public Collection<URI> getURIs() {
		return Collections.singletonList(uri);
	}

	@Override
	public String createID() {
		String id = getClass().getSimpleName() + ": " + uri.toString();
		if (args.length > 0) {
			id += "[" + String.join(", ", args) + "]";
		}
		return id;
	}

	/**
	 * Returns a builder capable of creating a server like this one.
	 */
	@Override
	protected ServerBuilder<BufferedImage> createServerBuilder() {
		return DefaultImageServerBuilder.createInstance(BioFormatsServerBuilder.class, getMetadata(), uri, args);
	}

	/**
	 * Get the series index, as used by Bio-Formats.
	 * @return
	 */
	public int getSeries() {
		return series;
	}


	@Override
	public BufferedImage readTile(TileRequest tileRequest) throws IOException {
		return readerPool.openImage(tileRequest, series, nChannels(), isRGB(), colorModel);
	}

	@Override
	public String getServerType() {
		return "Bio-Formats";
	}

	@Override
	public synchronized void close() throws Exception {
		super.close();
		readerPool.close();
	}

	boolean containsSubImages() {
		return imageMap != null && !imageMap.isEmpty();
	}

	@Override
	public List<String> getAssociatedImageList() {
		if (associatedImageMap == null || associatedImageMap.isEmpty())
			return Collections.emptyList();
		return new ArrayList<>(associatedImageMap.keySet());
	}

	@Override
	public BufferedImage getAssociatedImage(String name) {
		if (associatedImageMap == null || !associatedImageMap.containsKey(name))
			throw new IllegalArgumentException("No associated image with name '" + name + "' for " + getPath());

		try {
			return readerPool.openSeries(associatedImageMap.get(name));
		} catch (IOException e) {
			logger.error("Error when reading image", e);
			return null;
		}
	}

	/**
	 * Get the underlying file.
	 *
	 * @return
	 */
	public File getFile() {
		return filePathOrUrl == null ? null : new File(filePathOrUrl);
	}


	Map<String, ServerBuilder<BufferedImage>> getImageBuilders() {
		return Collections.unmodifiableMap(imageMap);
	}


	@Override
	public ImageServerMetadata getOriginalMetadata() {
		return originalMetadata;
	}

	/**
	 * Get the class name of the first reader that potentially supports the file type, or null if no reader can be found.
	 * <p>
	 * This method only uses the path and file extensions, generously returning the first potential
	 * reader based on the extension. Its purpose is to help filter out hopeless cases, not to establish
	 * the 'correct' reader.
	 *
	 * @param path
	 * @return
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 * @throws IllegalArgumentException
	 * @throws InvocationTargetException
	 * @throws NoSuchMethodException
	 * @throws SecurityException
	 */
	static String getSupportedReaderClass(String path) throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
		path = path.toLowerCase();
		for (var cls : ImageReader.getDefaultReaderClasses().getClasses()) {
			var reader = cls.getConstructor().newInstance();
			if (reader.isThisType(path, false))
				return cls.getName();
			for (String s : reader.getSuffixes()) {
				if (s != null && !s.isBlank() && path.endsWith(s.toLowerCase()))
					return cls.getName();
			}
		}
		return null;
	}

	public static class BioFormatsArgs {

		@Option(names = {"--series", "-s"}, defaultValue = "-1", description = "Series number (0-based, must be < image count for the file)")
		public
		int series = -1;

		@Option(names = {"--name", "-n"}, defaultValue = "", description = "Series name (legacy option, please use --series instead)")
		public
		String seriesName = "";

		@Option(names = {"--dims"}, defaultValue = "", description = "Swap dimensions. "
				+ "This should be a String of the form XYCZT, ordered according to how the image plans should be interpreted.")
		String swapDimensions = null;

		// Specific options used by some Bio-Formats readers, e.g. Map.of("zeissczi.autostitch", "false")
		@Option(names = {"--bfOptions"}, description = "Bio-Formats reader options")
		public
		Map<String, String> readerOptions = new LinkedHashMap<>();

		@Unmatched
		List<String> unmatched = new ArrayList<>();

		BioFormatsArgs() {}

		/**
		 * Return to an array of String args.
		 * @param series
		 * @return
		 */
		public String[] backToArgs(int series) {
			var args = new ArrayList<String>();
			if (series >= 0) {
				args.add("--series");
				args.add(Integer.toString(series));
			} else if (this.series >= 0) {
				args.add("--series");
				args.add(Integer.toString(this.series));
			} else if (seriesName != null && !seriesName.isBlank()) {
				args.add("--name");
				args.add(seriesName);
			}
			if (swapDimensions != null && !swapDimensions.isBlank()) {
				args.add("--dims");
				args.add(swapDimensions);
			}
			for (var option : readerOptions.entrySet()) {
				// Note: this assumes that options & values contain no awkwardness (e.g. quotes, spaces)
				args.add("--bfOptions");
				args.add(option.getKey()+"="+option.getValue());
			}
			args.addAll(unmatched);
			return args.toArray(String[]::new);
		}

		public String getSwapDimensions() {
			return swapDimensions == null || swapDimensions.isBlank() ? null : swapDimensions.toUpperCase();
		}

		static BioFormatsArgs parse(String[] args) {
			var bfArgs = new BioFormatsArgs();
			new CommandLine(bfArgs).parseArgs(args);
			return bfArgs;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((readerOptions == null) ? 0 : readerOptions.hashCode());
			result = prime * result + series;
			result = prime * result + ((seriesName == null) ? 0 : seriesName.hashCode());
			result = prime * result + ((swapDimensions == null) ? 0 : swapDimensions.hashCode());
			result = prime * result + ((unmatched == null) ? 0 : unmatched.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			BioFormatsArgs other = (BioFormatsArgs) obj;
			if (readerOptions == null) {
				if (other.readerOptions != null)
					return false;
			} else if (!readerOptions.equals(other.readerOptions))
				return false;
			if (series != other.series)
				return false;
			if (seriesName == null) {
				if (other.seriesName != null)
					return false;
			} else if (!seriesName.equals(other.seriesName))
				return false;
			if (swapDimensions == null) {
				if (other.swapDimensions != null)
					return false;
			} else if (!swapDimensions.equals(other.swapDimensions))
				return false;
			if (unmatched == null) {
				if (other.unmatched != null)
					return false;
			} else if (!unmatched.equals(other.unmatched))
				return false;
			return true;
		}

	}

	private static ClassList<IFormatReader> unwrapClasslist(IFormatReader reader) {
		while (reader instanceof loci.formats.ReaderWrapper)
			reader = ((loci.formats.ReaderWrapper)reader).getReader();
		var classlist = new ClassList<>(IFormatReader.class);
		classlist.addClass(reader.getClass());
		return classlist;
	}

}
