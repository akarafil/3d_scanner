#ifndef MESH_EXPORTER_H
#define MESH_EXPORTER_H

#include <vector>
#include <string>
#include "poisson_deferred.h"

class MeshExporter {
public:
    static bool ExportTexturedObj(
        const std::string& baseFilePath, 
        const std::vector<MeshVertex>& vertices, 
        const std::vector<MeshFace>& faces
    );
};

#endif // MESH_EXPORTER_H
