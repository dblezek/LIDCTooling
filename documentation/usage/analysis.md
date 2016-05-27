# Data analysis

## Overview

The data generated consists of NIfTI, and JSON, with an SQLite database summarizing all the processing.  The files generated for the read described `158.xml` are:

| File                        | Description |
| -----------                 | ----------- |
| `158.xml`                   | the original XML file containing read information |
| `image.nii.gz`              | A NIfTI version of the DICOM dataset |
| `reads.json`                | A JSON description of the XML file.  This file is more organized than the XML and, in particular, has normalized nodule numbers so nodules may be compared across readers. |
| `read_#.nii.gz`             | Labelmap NIfTI file of each segmented nodule for reader `#`. |
| `read_#_nodule_#.nii.gz`    | `GenerateLesionSegmentation` output corresponding to the centroid determined by reader `#` for `nodule_#`.  This file is a level set and is typically thresholded at `-4`. |
| `read_#_nodule_#_eval.json` | Distance measures comparing the human `read_#` to the algorithm results for `nodule_#`.  The metrics are described below. |


## Metrics

Metrics are generated by SimpleITK's [`LabelOverlapMeasuresImageFilter`](http://www.itk.org/SimpleITKDoxygen/html/classitk_1_1simple_1_1LabelOverlapMeasuresImageFilter.html) contributed in an Insight Journal paper ["Introducing Dice, Jaccard, and Other Label Overlap Measures To ITK"](http://www.insight-journal.org/browse/publication/707) by Nicholas J. Tustison, James C. Gee.  The metrics generated are:

| Metric   | Description |
| ------   | ----------- |
| false_negative_error |      |
| false_positive_error |      |
| mean_overlap |      |
| union_overlap |      |
| volume_similarity |      |
| jaccard_coefficient |      |
| dice_coefficient |      |
| hausdorff_distance |      |
| average_hausdorff_distance |      |

## SQLite Database

After the `lidc.sh` script generates all the files, `evaluate.sh` passes through the data and collects all the metrics into a [SQLite database](https://www.sqlite.org/).  The database contains many tables.

| Table  | Purpose |
| -----  | ------- |
| `nodules` | describes a nodule in terms of a `normalized_nodule_id` and the `series_uid` where it was found |
| `reads`   | describes a human reader's impression of a particular `nodule`.  Includes subjective description, and location of the nodule as determined by the reader. |
| `series`  | describes a LIDC DICOM series, related to `nodules` by `series_uid` |
| `measures` | describes the metrics generated by an automatic segmentation of nodule `nodule_uid`, of the read `read_uid` |

## Useful queries

SQLite provides a command line query interface with CSV output.

```bash
sqlite3 -header -csv test.db "select * from nodules"
```

### Select average Hausdorff distance for all reads

```bash
# spiculation_vs_hausdorff_distance.sql
select nodules.normalized_nodule_id, series.series_instance_uid, reads.*, measures.*

from
  nodules, series, reads, measures

where
  nodules.series_uid = series.uid
  and nodules.normalized_nodule_id = reads.normalized_nodule_id
  and reads.uid = measures.read_uid
  and measures.nodule_uid = nodules.uid

order by
  series.series_instance_uid, nodules.normalized_nodule_id;

sqlite3 -header -csv test.db ".read spiculation_vs_hausdorff_distance.sql" > spiculation_vs_hausdorff_distance.csv
```

### Sample plot using R

```
spiculation_vs_hausdorff_distance.R

png("spiculation_vs_hausdorff_distance.png", height = 1200, width = 1600)
d <- read.csv("spiculation_vs_hausdorff_distance.csv")
plot ( d$spiculation, d$average_hausdorff_distance, main="Segmentation distance vs. Spiculation", xlab="Spiculation (1-5)", ylab="Avg. Hausdorff Distance (mm)")
dev.off()
```

```
# generate the plot into spiculation_vs_hausdorff_distance.png
Rscript spiculation_vs_hausdorff_distance.R
```


## R explorations

```R
# Read in the CVS data
d <- read.csv("spiculation_vs_hausdorff_distance.csv")
# A spineplot gives the relative frequencies of each factor
spineplot(factor(margin)~factor(malignancy), data=d)

# Box plot of average_hausdorff_distance vs X
plot ( aggregate(average_hausdorff_distance~spiculation, d, mean ) )
```


## Table details

**nodules**

column | type
------ | ----
uid|text
series_uid|text
normalized_nodule_id|int

**reads**

column | type
------ | ----
uid|text
nodule_uid|text
normalized_nodule_id|int
id|text
centroid|text
centroidLPS|text
point_count|int
label_value|int
filled|int
subtlety|int
internalStructure|int
calcification|int
sphericity|int
margin|int
lobulation|int
spiculation|int
texture|int
malignancy|int

**series**

column | type
------ | ----
uid|text
series_instance_uid|text
study_instance_uid|text
patient_name|text
patient_id|text
manufacturer|text
manufacturer_model_name|text
patient_sex|text
patient_age|text
ethnic_group|text
contrast_bolus_agent|text
body_part_examined|text
scan_options|text
slice_thickness|float
kvp|float
data_collection_diameter|float
software_versions|text
reconstruction_diameter|float
gantry_detector_tilt|float
table_height|float
rotation_direction|text
exposure_time|float
xray_tube_current|float
exposure|float
convolution_kernel|text
patient_position|text
image_position_patient|text
image_orientation_patient|text
filename|text

**measure**

column | type
------ | ----
uid|text
nodule_uid|text
read_uid|text
command_line|text
false_negative_error|float
dice_coefficient|float
volume_similarity|float
false_positive_error|float
mean_overlap|float
union_overlap|float
jaccard_coefficient|float
hausdorff_distance|float
average_hausdorff_distance|float