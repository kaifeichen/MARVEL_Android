#pragma once

#include <rtabmap/core/Parameters.h>
#include <rtabmap/core/Memory.h>
#include <pcl/common/common.h>
#include <pcl/io/vtk_lib_io.h>

namespace rtabmap {

class Signature;

class MemoryLoc : public Memory
{
public:
    MemoryLoc(const ParametersMap & parameters = ParametersMap());

    void generateImages();
    Transform computeGlobalVisualTransform(const std::vector<int> & oldIds, int newId, std::string * rejectedMsg = 0, int * inliers = 0, double * variance = 0) const;
    Transform computeGlobalVisualTransform(const std::vector<Signature> & oldSs, const Signature & newS, std::string * rejectedMsg = 0, int * inliers = 0, double * variance = 0) const;

private:
    pcl::PolygonMesh::Ptr getMesh();
    void getClouds(std::map<int, pcl::PointCloud<pcl::PointXYZRGB>::Ptr > &clouds, 
                   std::map<int, Transform> &poses);
    pcl::PointCloud<pcl::PointXYZRGB>::Ptr assembleClouds(const std::map<int, pcl::PointCloud<pcl::PointXYZRGB>::Ptr > &clouds, 
                                                          const std::map<int, Transform> &poses,
                                                          std::vector<int> &rawCameraIndices);
    
private:
    int _bowMinInliers;
    int _bowIterations;
    int _bowRefineIterations;
    double _bowPnPReprojError;
    int _bowPnPFlags;

    float _voxelSize;
    float _filteringRadius;
    int _filteringMinNeighbors;
    float _MLSRadius;
    int _MLSpolygonialOrder;
    int _MLSUpsamplingMethod; // NONE, DISTINCT_CLOUD, SAMPLE_LOCAL_PLANE, RANDOM_UNIFORM_DENSITY, VOXEL_GRID_DILATION
    float _MLSUpsamplingRadius;
    float _MLSUpsamplingStep;
    int _MLSPointDensity;
    float _MLSDilationVoxelSize;
    int _MLSDilationIterations;
    int _normalK;
    float _gp3Radius;
    float _gp3Mu;
};

} // namespace rtabmap
