#include "include/SceneParameters.h"
#include "include/VectorMath.h"
#include <cstring>
#include <cmath>

SceneParameters::SceneParameters() {
    initializeDefaultLight();
}

void SceneParameters::initializeDefaultLight() {
    // Default directional light from top-right-front (normalized)
    lights[0].type = LightType::DIRECTIONAL;
    lights[0].direction[0] = 0.577350f;   // 0.5 / sqrt(0.75)
    lights[0].direction[1] = 0.577350f;   // 0.5 / sqrt(0.75)
    lights[0].direction[2] = -0.577350f;  // -0.5 / sqrt(0.75)
    lights[0].position[0] = 0.0f;
    lights[0].position[1] = 0.0f;
    lights[0].position[2] = 0.0f;
    lights[0].color[0] = 1.0f;
    lights[0].color[1] = 1.0f;
    lights[0].color[2] = 1.0f;
    lights[0].intensity = 1.0f;
}

void SceneParameters::setCamera(const float* eye, const float* lookAt, const float* up, float fov, int imageWidth, int imageHeight) {
    // Store eye position and FOV
    std::memcpy(camera.eye, eye, 3 * sizeof(float));
    camera.fov = fov;

    // Calculate W (view direction)
    camera.w[0] = lookAt[0] - eye[0];
    camera.w[1] = lookAt[1] - eye[1];
    camera.w[2] = lookAt[2] - eye[2];
    VectorMath::normalize3f(camera.w);

    // Calculate U (right = up × W)
    float u[3];
    VectorMath::cross3f(u, up, camera.w);
    VectorMath::normalize3f(u);

    // Calculate V (W × U)
    float v[3];
    VectorMath::cross3f(v, camera.w, u);

    // Scale by FOV and aspect ratio
    // IMPORTANT: fov parameter is HORIZONTAL FOV in degrees
    float aspect_ratio = static_cast<float>(imageWidth) / static_cast<float>(imageHeight);
    float ulen = std::tan(fov * 0.5f * RayTracingConstants::DEG_TO_RAD);
    float vlen = ulen / aspect_ratio;  // Vertical derived from horizontal

    camera.u[0] = u[0] * ulen;
    camera.u[1] = u[1] * ulen;
    camera.u[2] = u[2] * ulen;

    camera.v[0] = v[0] * vlen;
    camera.v[1] = v[1] * vlen;
    camera.v[2] = v[2] * vlen;

    camera.dirty = true;
}

void SceneParameters::setSphere(float x, float y, float z, float radius) {
    sphere.center[0] = x;
    sphere.center[1] = y;
    sphere.center[2] = z;
    sphere.radius = radius;
    sphere.dirty = true;
}

void SceneParameters::setSphereColor(float r, float g, float b, float a) {
    sphere.color[0] = r;
    sphere.color[1] = g;
    sphere.color[2] = b;
    sphere.color[3] = a;
    sphere.dirty = true;
}

void SceneParameters::setIOR(float ior) {
    sphere.ior = ior;
    sphere.dirty = true;
}

void SceneParameters::setScale(float scale) {
    sphere.scale = scale;
    sphere.dirty = true;
}

void SceneParameters::setPlane(int axis, bool positive, float value) {
    plane.axis = axis;
    plane.positive = positive;
    plane.value = value;
    plane.dirty = true;
}

void SceneParameters::setLights(const Light* lightsArray, int count) {
    if (lightsArray == nullptr && count > 0) {
        return;  // Invalid input
    }

    num_lights = count;
    for (int i = 0; i < count; ++i) {
        lights[i] = lightsArray[i];
    }
}

void SceneParameters::setTriangleMeshMeta(unsigned int numVertices, unsigned int numTriangles) {
    triangle_mesh.num_vertices = numVertices;
    triangle_mesh.num_triangles = numTriangles;
    triangle_mesh.has_mesh = (numVertices > 0 && numTriangles > 0);
    triangle_mesh.dirty = true;
}

void SceneParameters::setTriangleMeshColor(float r, float g, float b, float a) {
    triangle_mesh.color[0] = r;
    triangle_mesh.color[1] = g;
    triangle_mesh.color[2] = b;
    triangle_mesh.color[3] = a;
    triangle_mesh.dirty = true;
}

void SceneParameters::setTriangleMeshIOR(float ior) {
    triangle_mesh.ior = ior;
    triangle_mesh.dirty = true;
}

void SceneParameters::clearTriangleMesh() {
    triangle_mesh.num_vertices = 0;
    triangle_mesh.num_triangles = 0;
    triangle_mesh.has_mesh = false;
    triangle_mesh.dirty = true;
}

bool SceneParameters::isAnyDirty() const {
    return camera.dirty || sphere.dirty || plane.dirty || triangle_mesh.dirty;
}

void SceneParameters::clearDirtyFlags() {
    camera.dirty = false;
    sphere.dirty = false;
    plane.dirty = false;
    triangle_mesh.dirty = false;
}
