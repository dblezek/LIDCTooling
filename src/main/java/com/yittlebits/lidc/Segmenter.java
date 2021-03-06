package com.yittlebits.lidc;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yittlebits.lidc.fill.Fill;

import org.apache.commons.cli.CommandLine;
import org.dcm4che2.data.DicomObject;
import org.dcm4che2.data.Tag;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import niftijio.NiftiHeader;
import niftijio.NiftiVolume;

public class Segmenter {
    static Logger logger = Logger.getLogger(Segmenter.class.getName());

    Document doc = null;
    XPath xpath;
    File inputDirectory;
    File outputDirectory;

    // Sorted by Tag.SliceLocation
    List<DicomObject> dicomObjects;

    // Indexed by SOPInstanceUID
    Map<String, DicomObject> uidToInstance;
    Map<String, Integer> uidToIndex = new HashMap<>();

    // Volume size
    int nx, ny, nz;

    public void segment(CommandLine cl) throws Exception {
	if (cl.getArgList().size() < 4) {
	    Extract.printUsageAndDie();
	}

	String xmlFile = cl.getArgList().get(1);
	inputDirectory = new File(cl.getArgs()[2]);
	if (!inputDirectory.exists() || !inputDirectory.isDirectory()) {
	    System.err.println("input directory " + inputDirectory + " must exist and be a directory");
	    Extract.printUsageAndDie();
	}

	outputDirectory = new File(cl.getArgs()[3]);
	if (!outputDirectory.exists() || !outputDirectory.isDirectory()) {
	    System.err.println("output directory " + outputDirectory + " must exist and be a directory");
	    Extract.printUsageAndDie();
	}

	DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
	factory.setNamespaceAware(false);
	factory.setValidating(false);
	DocumentBuilder builder = factory.newDocumentBuilder();
	doc = builder.parse(xmlFile);
	xpath = XPathFactory.newInstance().newXPath();

	// Load DICOM
	loadDICOM();
	logger.fine("Loaded " + dicomObjects.size() + " DICOM from " + inputDirectory);
	for (DicomObject dicom : dicomObjects) {
	    logger.fine("Slice location: " + dicom.getDouble(Tag.SliceLocation));
	}

	// Save each reading session as a NII file
	// Save reader images
	saveReaderImages();

    }

    List<Node> toList(NodeList nodeList) {
	ArrayList<Node> list = new ArrayList<>();
	for (int i = 0; i < nodeList.getLength(); i++) {
	    list.add(nodeList.item(i));
	}
	return list;
    }

    private String getString(String expression, Node node) throws Exception {
	return (String) xpath.compile(expression).evaluate(node, XPathConstants.STRING);
    }

    private Double getDouble(String expression, Node node) throws Exception {
	return (Double) xpath.evaluate(expression, node, XPathConstants.NUMBER);
    }

    private NiftiVolume createVolume() {
	double[] pixelSpacing = dicomObjects.get(0).getDoubles(Tag.PixelSpacing);
	double sliceSpacing = dicomObjects.get(0).getDouble(Tag.SliceThickness);
	NiftiVolume volume = new NiftiVolume(nx, ny, nz, 1);
	volume.header.setDatatype(NiftiHeader.NIFTI_TYPE_INT16);
	volume.header.pixdim[1] = (float) pixelSpacing[0];
	volume.header.pixdim[2] = (float) pixelSpacing[1];
	volume.header.pixdim[3] = (float) sliceSpacing;
	volume.header.xyzt_units = NiftiHeader.NIFTI_UNITS_MM;

	return volume;
    }

    private void saveReaderImages() throws Exception {

	ObjectMapper mapper = new ObjectMapper();
	ObjectNode json = mapper.createObjectNode();

	String seriesInstanceUID = (String) xpath.compile("/LidcReadMessage/ResponseHeader/SeriesInstanceUid/text()")
		.evaluate(doc, XPathConstants.STRING);
	String studyInstanceUID = (String) xpath.compile("/LidcReadMessage/ResponseHeader/StudyInstanceUID/text()")
		.evaluate(doc, XPathConstants.STRING);
	json.put("series_instance_uid", seriesInstanceUID);
	json.put("uid", seriesInstanceUID);
	json.put("study_instance_uid", studyInstanceUID);

	DicomObject slice = dicomObjects.get(0);
	json.put("patient_name", slice.getString(Tag.PatientName));
	json.put("patient_id", slice.getString(Tag.PatientID));
	json.put("manufacturer", slice.getString(Tag.Manufacturer));
	json.put("manufacturer_model_name", slice.getString(Tag.ManufacturerModelName));
	json.put("patient_sex", slice.getString(Tag.PatientSex));
	json.put("patient_age", slice.getString(Tag.PatientAge));
	json.put("ethnic_group", slice.getString(Tag.EthnicGroup));
	json.put("contrast_bolus_agent", slice.getString(Tag.ContrastBolusAgent));
	json.put("body_part_examined", slice.getString(Tag.BodyPartExamined));
	json.put("scan_options", slice.getString(Tag.ScanOptions));
	json.put("slice_thickness", slice.getDouble(Tag.SliceThickness));
	json.put("kvp", slice.getDouble(Tag.KVP));
	json.put("data_collection_diameter", slice.getDouble(Tag.DataCollectionDiameter));
	json.put("software_versions", slice.getString(Tag.SoftwareVersions));
	json.put("reconstruction_diameter", slice.getDouble(Tag.ReconstructionDiameter));
	json.put("gantry_detector_tilt", slice.getDouble(Tag.GantryDetectorTilt));
	json.put("table_height", slice.getDouble(Tag.TableHeight));
	json.put("rotation_direction", slice.getString(Tag.RotationDirection));
	json.put("exposure_time", slice.getInt(Tag.ExposureTime));
	json.put("xray_tube_current", slice.getInt(Tag.XRayTubeCurrent));
	json.put("exposure", slice.getInt(Tag.Exposure));
	json.put("convolution_kernel", slice.getString(Tag.ConvolutionKernel));
	json.put("patient_position", slice.getString(Tag.PatientPosition));

	ArrayNode a = json.putArray("image_position_patient");
	for (double v : slice.getDoubles(Tag.ImagePositionPatient)) {
	    a.add(v);
	}
	a = json.putArray("image_orientation_patient");
	for (double v : slice.getDoubles(Tag.ImageOrientationPatient)) {
	    a.add(v);
	}

	// Save base image
	String baseImageFilename = saveBaseImage();
	json.put("filename", baseImageFilename);

	// Normalize nodule ids by tracking distance to all other nodules. Set a
	// minimum distance in mm for merging nodules.
	NoduleNormalizer normalizer = new NoduleNormalizer(50);

	// Loop over each
	ArrayNode readArray = json.putArray("reads");
	NodeList reads = (NodeList) xpath.compile("/LidcReadMessage/readingSession").evaluate(doc,
		XPathConstants.NODESET);
	int readIndex = -1;
	for (Node read : toList(reads)) {
	    readIndex++;
	    String filename = "read_" + readIndex + ".nii.gz";
	    ObjectNode readNode = readArray.addObject();
	    readNode.put("filename", filename);
	    readNode.put("id", readIndex);
	    readNode.put("uid", seriesInstanceUID + "-" + readIndex);
	    NiftiVolume volume = createVolume();
	    ArrayNode nodules = readNode.putArray("nodules");
	    ArrayNode smallNodules = readNode.putArray("small_nodules");

	    // Find all the nodules
	    for (Node nodule : toList(
		    (NodeList) xpath.evaluate("./unblindedReadNodule", read, XPathConstants.NODESET))) {
		boolean isLarge = (Double) xpath.evaluate("count(./roi/edgeMap)", nodule, XPathConstants.NUMBER) > 1.0;
		ObjectNode noduleNode;
		if (isLarge) {
		    noduleNode = nodules.addObject();
		    // Add extra info
		    ObjectNode characteristics = noduleNode.putObject("characteristics");
		    characteristics.put("subtlety", getDouble("./characteristics/subtlety", nodule).intValue());
		    characteristics.put("internalStructure",
			    getDouble("./characteristics/internalStructure", nodule).intValue());
		    characteristics.put("calcification",
			    getDouble("./characteristics/calcification", nodule).intValue());
		    characteristics.put("sphericity", getDouble("./characteristics/sphericity", nodule).intValue());
		    characteristics.put("margin", getDouble("./characteristics/margin", nodule).intValue());
		    characteristics.put("lobulation", getDouble("./characteristics/lobulation", nodule).intValue());
		    characteristics.put("spiculation", getDouble("./characteristics/spiculation", nodule).intValue());
		    characteristics.put("texture", getDouble("./characteristics/texture", nodule).intValue());
		    characteristics.put("malignancy", getDouble("./characteristics/malignancy", nodule).intValue());
		} else {
		    noduleNode = smallNodules.addObject();
		}

		noduleNode.put("id", getString("./noduleID/text()", nodule));

		double cx = 0.0, cy = 0.0, cz = 0.0;
		int pointCount = 0;
		Set<Integer> roiSlices = new HashSet<>();
		Map<Integer, ArrayList<Point3>> contourMap = new HashMap<>();
		for (Node roi : toList((NodeList) xpath.evaluate("./roi", nodule, XPathConstants.NODESET))) {
		    String instanceUID = (String) xpath.evaluate("./imageSOP_UID/text()", roi, XPathConstants.STRING);
		    logger.fine("\tROI on slice: " + instanceUID);
		    if (uidToInstance.containsKey(instanceUID)) {
			logger.fine("\tfound in the slices!");
			logger.fine("\tIs at slice #" + uidToIndex.get(instanceUID));
		    } else {
			throw new Exception(
				"Failed to find UID " + instanceUID + " in the slices in " + inputDirectory);
		    }

		    int z = uidToIndex.get(instanceUID);
		    roiSlices.add(z);
		    ArrayList<Point3> pointList = new ArrayList<>();
		    // Find the Regions
		    for (Node point : toList((NodeList) xpath.evaluate("./edgeMap", roi, XPathConstants.NODESET))) {
			double x = (Double) xpath.evaluate("./xCoord/text()", point, XPathConstants.NUMBER);
			double y = (Double) xpath.evaluate("./yCoord/text()", point, XPathConstants.NUMBER);
			cx += x;
			cy += y;
			cz += z;
			pointList.add(new Point3((int) x, (int) y, z));
			pointCount++;

			// Write to the file
			// volume.data.set((int) x, (int) y, z, 0, labelValue);
		    }
		    if (pointList.size() > 0) {
			contourMap.put(z, pointList);
		    }
		}
		cx = cx / pointCount;
		cy = cy / pointCount;
		cz = cz / pointCount;
		ArrayNode centroid = noduleNode.putArray("centroid");
		centroid.add(cx);
		centroid.add(cy);
		centroid.add(cz);
		double[] pixelSpacing = dicomObjects.get(0).getDoubles(Tag.PixelSpacing);
		double sliceSpacing = dicomObjects.get(0).getDouble(Tag.SliceThickness);

		DoublePoint3 centroidPoint = new DoublePoint3(cx * pixelSpacing[0], cy * pixelSpacing[1],
			cz * sliceSpacing);
		ArrayNode centroidLPS = noduleNode.putArray("centroidLPS");
		centroidLPS.add(centroidPoint.x);
		centroidLPS.add(centroidPoint.y);
		centroidLPS.add(centroidPoint.z);

		int normalizeNoduleIdx = normalizer.getNormalizedId(centroidPoint);

		// Fill with normalizedNoduleIdx
		for (Entry<Integer, ArrayList<Point3>> entry : contourMap.entrySet()) {
		    Fill.drawPolygon(volume, entry.getValue().toArray(new Point3[entry.getValue().size()]),
			    normalizeNoduleIdx, entry.getKey());
		}

		noduleNode.put("point_count", pointCount);
		noduleNode.put("label_value", normalizeNoduleIdx);
		noduleNode.put("normalized_nodule_id", normalizeNoduleIdx);
		noduleNode.put("uid", seriesInstanceUID + "-" + readIndex + "." + normalizeNoduleIdx);
	    }
	    logger.fine("Writing Read: " + readIndex);
	    volume.write(new File(outputDirectory, filename).getPath());
	}

	ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter());
	writer.writeValue(new File(outputDirectory, "reads.json"), json);
    }

    /** Returns status of fill, false means we exceeded limits. */
    private boolean fill(NiftiVolume volume, double cx, double cy, double cz, int labelValue) {
	Stack<Point3> stack = new Stack<>();
	stack.add(new Point3((int) cx, (int) cy, (int) cz));
	int count = 0;
	while (!stack.isEmpty()) {
	    Point3 p = stack.pop();
	    count++;
	    double v = volume.data.get(p.x, p.y, p.z, 0);
	    if (v != labelValue) {
		volume.data.set(p.x, p.y, p.z, 0, labelValue);
		stack.push(new Point3(p.x, p.y + 1, p.z));
		stack.push(new Point3(p.x, p.y - 1, p.z));
		stack.push(new Point3(p.x + 1, p.y, p.z));
		stack.push(new Point3(p.x - 1, p.y, p.z));
	    }
	    if (count > 4000) {
		return false;
	    }
	}
	return true;
    }

    private String saveBaseImage() throws Exception {
	NiftiVolume volume = createVolume();

	for (int k = 0; k < nz; k++) {
	    DicomObject slice = dicomObjects.get(k);
	    float rescaleSlope = slice.getFloat(Tag.RescaleSlope);
	    float rescaleIntercept = slice.getFloat(Tag.RescaleIntercept);
	    short[] pixelData = slice.getShorts(Tag.PixelData);
	    short pixelPaddingValue = (short) slice.getInt(Tag.PixelPaddingValue);

	    int idx = 0;
	    for (int j = 0; j < ny; j++) {
		for (int i = 0; i < nx; i++) {

		    double newPixel = pixelData[idx];
		    idx++;
		    double tmp = rescaleSlope * newPixel + rescaleIntercept;
		    newPixel = tmp;

		    if (newPixel == pixelPaddingValue) {
			newPixel = 0.0;
		    }
		    // Flip Y...
		    volume.data.set(i, j, k, 0, newPixel);
		}
	    }
	}
	logger.fine("Saving example volume");
	String filename = "image.nii.gz";
	volume.write(new File(outputDirectory, filename).getPath());
	return filename;
    }

    private void loadDICOM() throws Exception {
	dicomObjects = Files.walk(inputDirectory.toPath()).filter(p -> {
	    return !p.toFile().isDirectory();
	}).map(p -> {
	    try {
		return TagLoader.loadTags(p.toFile(), true);
	    } catch (Exception e) {
		return null;
	    }
	}).filter((d) -> {
	    // ignore any bad files
	    return d != null;
	}).sorted((d1, d2) -> {
	    return Double.compare(d1.getDouble(Tag.SliceLocation, -1000000.0),
		    d2.getDouble(Tag.SliceLocation, -100000.0));
	}).collect(Collectors.toList());

	IntStream.range(0, dicomObjects.size()).forEach(idx -> {
	    uidToIndex.put(dicomObjects.get(idx).getString(Tag.SOPInstanceUID), idx);
	});

	// index by uid, how easy is that!
	uidToInstance = dicomObjects.stream().collect(Collectors.toMap(item -> {
	    return item.getString(Tag.SOPInstanceUID);
	}, item -> item));
	// Get the image dimensions
	ny = dicomObjects.get(0).getInt(Tag.Rows);
	nx = dicomObjects.get(0).getInt(Tag.Columns);
	nz = dicomObjects.size();
    }
}
