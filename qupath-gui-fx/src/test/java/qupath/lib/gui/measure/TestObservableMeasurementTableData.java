/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
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

package qupath.lib.gui.measure;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Some tests for observable measurements.
 * 
 * @author Pete Bankhead
 *
 */
public class TestObservableMeasurementTableData {

	private static final double EPSILON = 1e-15;
	
	
	@SuppressWarnings("javadoc")
	@Test
	public void test() {
		
		// Test multiple times
		// The reason is that there was a subtle multithreading bug, introduced when switching to JTS 1.17.0.
		// This showed up intermittently in tests.
		// See https://github.com/locationtech/jts/issues/571
		for (int counter = 0; counter < 50; counter++) {
		
			ImageData<BufferedImage> imageData = new ImageData<>(
					new WrappedBufferedImageServer("Dummy",
							new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB)));
			
			PathClass tumorClass = PathClass.StandardPathClasses.TUMOR;
			PathClass stromaClass = PathClass.StandardPathClasses.STROMA;
	//		PathClass otherClass = PathClassFactory.getDefaultPathClass(PathClasses.OTHER);
			PathClass artefactClass = PathClass.getInstance("Artefact");
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			
			// Add a parent annotation
			PathObject parent = PathObjects.createAnnotationObject(
									ROIs.createRectangleROI(500, 500, 1000, 1000, ImagePlane.getDefaultPlane())
									);
			
			
			// Create 100 tumor detections
	//		ROI emptyROI = ROIs.createEmptyROI();
			ROI smallROI = ROIs.createRectangleROI(500, 500, 1, 1, ImagePlane.getDefaultPlane());
			for (int i = 0; i < 100; i++) {
				if (i < 25)
					parent.addChildObject(PathObjects.createDetectionObject(smallROI, PathClass.getNegative(tumorClass)));
				else if (i < 50)
					parent.addChildObject(PathObjects.createDetectionObject(smallROI, PathClass.getOnePlus(tumorClass)));
				else if (i < 75)
					parent.addChildObject(PathObjects.createDetectionObject(smallROI, PathClass.getTwoPlus(tumorClass)));
				else if (i < 100)
					parent.addChildObject(PathObjects.createDetectionObject(smallROI, PathClass.getThreePlus(tumorClass)));
			}
			// Create 100 stroma detections
			for (int i = 0; i < 100; i++) {
				if (i < 50)
					parent.addChildObject(PathObjects.createDetectionObject(smallROI, PathClass.getNegative(stromaClass)));
				else if (i < 60)
					parent.addChildObject(PathObjects.createDetectionObject(smallROI, PathClass.getOnePlus(stromaClass)));
				else if (i < 70)
					parent.addChildObject(PathObjects.createDetectionObject(smallROI, PathClass.getTwoPlus(stromaClass)));
				else if (i < 100)
					parent.addChildObject(PathObjects.createDetectionObject(smallROI, PathClass.getThreePlus(stromaClass)));
			}
			// Create 50 artefact detections
			for (int i = 0; i < 50; i++) {
				parent.addChildObject(PathObjects.createDetectionObject(smallROI, artefactClass));
			}
			
			hierarchy.addObject(parent);
			
			ObservableMeasurementTableData model = new ObservableMeasurementTableData();
			model.setImageData(imageData, Collections.singletonList(parent));
			
			
			// Check tumor counts
			assertEquals(100, model.getNumericValue(parent, "Num Tumor (base)"), EPSILON);
			assertEquals(25, model.getNumericValue(parent, "Num Tumor: Negative"), EPSILON);
			assertEquals(25, model.getNumericValue(parent, "Num Tumor: 1+"), EPSILON);
			assertEquals(25, model.getNumericValue(parent, "Num Tumor: 2+"), EPSILON);
			assertEquals(25, model.getNumericValue(parent, "Num Tumor: 3+"), EPSILON);
			assertTrue(Double.isNaN(model.getNumericValue(parent, "Num Tumor: 4+")));
			
			// Check tumor H-score, Allred score & positive %
			assertEquals(150, model.getNumericValue(parent, "Tumor: H-score"), EPSILON);
			assertEquals(75, model.getNumericValue(parent, "Tumor: Positive %"), EPSILON);
			assertEquals(2, model.getNumericValue(parent, "Tumor: Allred intensity"), EPSILON);
			assertEquals(5, model.getNumericValue(parent, "Tumor: Allred proportion"), EPSILON);
			assertEquals(7, model.getNumericValue(parent, "Tumor: Allred score"), EPSILON);
	
			// Check tumor H-score unaffected when tumor detections added without intensity classification
			for (int i = 0; i < 10; i++)
				parent.addChildObject(PathObjects.createDetectionObject(smallROI, tumorClass));
			hierarchy.fireHierarchyChangedEvent(this);

	//		model.setImageData(imageData, Collections.singletonList(parent));
			assertEquals(100, model.getNumericValue(parent, "Num Stroma (base)"), EPSILON);
			assertEquals(50, model.getNumericValue(parent, "Num Stroma: Negative"), EPSILON);
			assertEquals(150, model.getNumericValue(parent, "Tumor: H-score"), EPSILON);
			assertEquals(75, model.getNumericValue(parent, "Tumor: Positive %"), EPSILON);
			
			// Check stroma scores
			assertEquals(100, model.getNumericValue(parent, "Num Stroma (base)"), EPSILON);
			assertEquals(120, model.getNumericValue(parent, "Stroma: H-score"), EPSILON);
	
			// Check complete scores
			assertEquals(135, model.getNumericValue(parent, "Stroma + Tumor: H-score"), EPSILON);
			
			// Add a new parent that completely contains the current object, and confirm complete scores agree
			PathObject parentNew = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 2000, 2000, ImagePlane.getDefaultPlane()));
			hierarchy.addObject(parentNew);

			assertEquals(135, model.getNumericValue(parent, "Stroma + Tumor: H-score"), EPSILON);
			assertEquals(135, model.getNumericValue(parentNew, "Stroma + Tumor: H-score"), EPSILON);
			
			// Create a new object and demonstrate Allred dependence on a single cell
			PathObject parentAllred = PathObjects.createAnnotationObject(ROIs.createRectangleROI(4000, 4000, 1000, 1000, ImagePlane.getDefaultPlane()));
			ROI newROI = ROIs.createEllipseROI(4500, 4500, 10, 10, ImagePlane.getDefaultPlane());
			for (int i = 0; i < 100; i++)
				parentAllred.addChildObject(PathObjects.createDetectionObject(newROI, PathClass.getNegative(tumorClass)));
			hierarchy.addObject(parentAllred);

			assertEquals(0, model.getNumericValue(parentAllred, "Tumor: Allred score"), EPSILON);
			parentAllred.addChildObject(PathObjects.createDetectionObject(newROI, PathClass.getThreePlus(tumorClass)));
			hierarchy.fireHierarchyChangedEvent(parentAllred);

			assertEquals(4, model.getNumericValue(parentAllred, "Tumor: Allred score"), EPSILON);
		
		}
	}

}
