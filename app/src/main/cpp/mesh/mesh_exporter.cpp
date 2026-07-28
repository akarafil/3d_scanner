#include "mesh_exporter.h"
#include <android/log.h>
#include <fstream>
#include <cmath>
#include <algorithm>
#include "../third_party/xatlas/xatlas.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "../third_party/stb/stb_image_write.h"

#define LOG_TAG "MeshExporter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Barycentric interpolation helper
struct Vec2 { float x, y; };
struct Vec3 { float x, y, z; };

float EdgeFunction(const Vec2& a, const Vec2& b, const Vec2& c) {
    return (c.x - a.x) * (b.y - a.y) - (c.y - a.y) * (b.x - a.x);
}

bool MeshExporter::ExportTexturedObj(const std::string& baseFilePath, const std::vector<MeshVertex>& vertices, const std::vector<MeshFace>& faces) {
    LOGI("Starting UV Unwrapping with xatlas...");

    xatlas::Atlas* atlas = xatlas::Create();

    xatlas::MeshDecl meshDecl;
    meshDecl.vertexCount = vertices.size();
    meshDecl.vertexPositionData = vertices.data();
    meshDecl.vertexPositionStride = sizeof(MeshVertex);
    
    // xatlas expects uint32_t indices, but MeshFace uses int. We need to copy them.
    std::vector<uint32_t> indices(faces.size() * 3);
    for (size_t i = 0; i < faces.size(); ++i) {
        indices[i * 3 + 0] = faces[i].v1;
        indices[i * 3 + 1] = faces[i].v2;
        indices[i * 3 + 2] = faces[i].v3;
    }
    meshDecl.indexCount = indices.size();
    meshDecl.indexData = indices.data();
    meshDecl.indexFormat = xatlas::IndexFormat::UInt32;

    xatlas::AddMeshError error = xatlas::AddMesh(atlas, meshDecl);
    if (error != xatlas::AddMeshError::Success) {
        LOGE("xatlas::AddMesh failed!");
        xatlas::Destroy(atlas);
        return false;
    }

    xatlas::Generate(atlas);

    const int texWidth = 2048;
    const int texHeight = 2048;
    std::vector<uint8_t> texture(texWidth * texHeight * 3, 0); // Siyah arkaplan

    LOGI("UV Unwrapping done. Baking texture...");

    // Yeni vertex ve UV verilerini alıyoruz
    const xatlas::Mesh& mesh = atlas->meshes[0];
    
    struct ExportVertex {
        float x, y, z;
        float nx, ny, nz;
        float u, v;
    };
    std::vector<ExportVertex> exportVertices(mesh.vertexCount);

    for (uint32_t i = 0; i < mesh.vertexCount; i++) {
        const xatlas::Vertex& v = mesh.vertexArray[i];
        const MeshVertex& orig = vertices[v.xref];
        exportVertices[i] = {
            orig.x, orig.y, orig.z,
            orig.nx, orig.ny, orig.nz,
            v.uv[0] / atlas->width, 
            v.uv[1] / atlas->height
        };
    }

    // Basit CPU Rasterizer: Her üçgeni texture üzerine boyar
    for (uint32_t i = 0; i < mesh.indexCount; i += 3) {
        uint32_t idx0 = mesh.indexArray[i];
        uint32_t idx1 = mesh.indexArray[i + 1];
        uint32_t idx2 = mesh.indexArray[i + 2];

        const xatlas::Vertex& v0 = mesh.vertexArray[idx0];
        const xatlas::Vertex& v1 = mesh.vertexArray[idx1];
        const xatlas::Vertex& v2 = mesh.vertexArray[idx2];

        const MeshVertex& orig0 = vertices[v0.xref];
        const MeshVertex& orig1 = vertices[v1.xref];
        const MeshVertex& orig2 = vertices[v2.xref];

        // UV'leri piksel koordinatlarına çevir
        Vec2 p0 = { v0.uv[0] / atlas->width * texWidth, v0.uv[1] / atlas->height * texHeight };
        Vec2 p1 = { v1.uv[0] / atlas->width * texWidth, v1.uv[1] / atlas->height * texHeight };
        Vec2 p2 = { v2.uv[0] / atlas->width * texWidth, v2.uv[1] / atlas->height * texHeight };

        // Bounding box
        int minX = std::max(0, (int)std::floor(std::min({p0.x, p1.x, p2.x})));
        int minY = std::max(0, (int)std::floor(std::min({p0.y, p1.y, p2.y})));
        int maxX = std::min(texWidth - 1, (int)std::ceil(std::max({p0.x, p1.x, p2.x})));
        int maxY = std::min(texHeight - 1, (int)std::ceil(std::max({p0.y, p1.y, p2.y})));

        float area = EdgeFunction(p0, p1, p2);
        if (std::abs(area) < 0.0001f) continue;

        for (int y = minY; y <= maxY; ++y) {
            for (int x = minX; x <= maxX; ++x) {
                Vec2 p = { (float)x + 0.5f, (float)y + 0.5f };
                
                float w0 = EdgeFunction(p1, p2, p);
                float w1 = EdgeFunction(p2, p0, p);
                float w2 = EdgeFunction(p0, p1, p);

                if (w0 >= 0 && w1 >= 0 && w2 >= 0) {
                    w0 /= area;
                    w1 /= area;
                    w2 /= area;

                    float r = w0 * orig0.r + w1 * orig1.r + w2 * orig2.r;
                    float g = w0 * orig0.g + w1 * orig1.g + w2 * orig2.g;
                    float b = w0 * orig0.b + w1 * orig1.b + w2 * orig2.b;

                    int pixIdx = (y * texWidth + x) * 3;
                    texture[pixIdx + 0] = std::clamp((int)r, 0, 255);
                    texture[pixIdx + 1] = std::clamp((int)g, 0, 255);
                    texture[pixIdx + 2] = std::clamp((int)b, 0, 255);
                }
            }
        }
    }

    LOGI("Baking done. Saving files...");

    std::string objPath = baseFilePath + ".obj";
    std::string mtlPath = baseFilePath + ".mtl";
    std::string pngPath = baseFilePath + ".png";

    // OBJ'de referans için sadece isim yeterli
    size_t lastSlash = baseFilePath.find_last_of('/');
    std::string baseName = (lastSlash == std::string::npos) ? baseFilePath : baseFilePath.substr(lastSlash + 1);
    std::string mtlName = baseName + ".mtl";
    std::string pngName = baseName + ".png";

    // 1. Save PNG
    stbi_write_png(pngPath.c_str(), texWidth, texHeight, 3, texture.data(), texWidth * 3);

    // 2. Save MTL
    std::ofstream mtlFile(mtlPath);
    mtlFile << "newmtl material_0\n";
    mtlFile << "Ka 1.000 1.000 1.000\n";
    mtlFile << "Kd 1.000 1.000 1.000\n";
    mtlFile << "Ks 0.000 0.000 0.000\n";
    mtlFile << "map_Kd " << pngName << "\n";
    mtlFile.close();

    // 3. Save OBJ
    std::ofstream objFile(objPath);
    objFile << "mtllib " << mtlName << "\n";
    objFile << "usemtl material_0\n";

    for (const auto& v : exportVertices) {
        objFile << "v " << v.x << " " << v.y << " " << v.z << "\n";
    }
    for (const auto& v : exportVertices) {
        objFile << "vt " << v.u << " " << (1.0f - v.v) << "\n"; // Y'yi ters çevir (OBJ vs PNG uv farklılıkları)
    }
    for (const auto& v : exportVertices) {
        objFile << "vn " << v.nx << " " << v.ny << " " << v.nz << "\n";
    }

    for (uint32_t i = 0; i < mesh.indexCount; i += 3) {
        uint32_t i0 = mesh.indexArray[i] + 1;
        uint32_t i1 = mesh.indexArray[i + 1] + 1;
        uint32_t i2 = mesh.indexArray[i + 2] + 1;
        objFile << "f " << i0 << "/" << i0 << "/" << i0 << " "
                << i1 << "/" << i1 << "/" << i1 << " "
                << i2 << "/" << i2 << "/" << i2 << "\n";
    }

    objFile.close();
    xatlas::Destroy(atlas);
    LOGI("Export completed successfully: %s", objPath.c_str());
    return true;
}
