# Evaluate lung nodule segmentation

usage = """
Usage:
 evaluateSegmentation.py [options] segmentation.nii gold_standard.nii evaluation.json
 options:
    --label <value>        -- label in gold_standard to compare, default is 1
    --threshold <value>    -- threshold in segmentation.nii, default is -0.5
    --cli <value>          -- command used to generate this segmentation
"""

import getopt
import SimpleITK as sitk
import sys,os,json
import math


# Parse 
opts,args = getopt.getopt(sys.argv[1:], "", longopts=["label=","threshold=","cli="])

if len(args) < 3:
    print usage
    sys.exit(1)

settings = { "--threshold": '1.0', "--label": '1'}
settings.update ( opts )

# Load the input image
segmentation = sitk.ReadImage ( args[0] )
gold_standard = sitk.ReadImage ( args[1] )
jsonOutput = args[2]

segmentation = sitk.BinaryThreshold(segmentation, lowerThreshold=1.0, upperThreshold=1000)
label = float(settings['--label'])
gold_standard = sitk.BinaryThreshold(gold_standard, lowerThreshold=label, upperThreshold=label)

# Compute overlap
ruler = sitk.LabelOverlapMeasuresImageFilter()
overlap = ruler.Execute(segmentation,gold_standard)

# Compute Hausdorff distance, this may fail if the segmentation failed
hd = sitk.HausdorffDistanceImageFilter()
try:
    hd.Execute(segmentation,gold_standard)
except:
    pass

measures = {
    "command_line": settings["--cli"],
    "measures" : {
        "false_negative_error": ruler.GetFalseNegativeError(),
        "false_positive_error": 1.0 if math.isnan(ruler.GetFalsePositiveError()) else ruler.GetFalsePositiveError(),
        "mean_overlap": ruler.GetMeanOverlap(),
        "union_overlap": ruler.GetUnionOverlap(),
        "volume_similarity": ruler.GetVolumeSimilarity(),
        "jaccard_coefficient": ruler.GetJaccardCoefficient(),
        "dice_coefficient": ruler.GetDiceCoefficient(),
        "hausdorff_distance": hd.GetHausdorffDistance(),
        "average_hausdorff_distance": hd.GetAverageHausdorffDistance(),
    }
}



fid = open(jsonOutput, 'w')
fid.write(json.dumps(measures, indent=2))
fid.close()
