// Standalone test for Valgrind memory leak detection
// Compile: g++ -std=c++17 -I./include -I/usr/local/cuda/include -I$OPTIX_ROOT/include \
//          -L/usr/local/cuda/lib64 -L./target/native/x86_64-linux/bin \
//          standalone_test.cpp -loptixjni -lcudart -o standalone_test
// Run: LD_LIBRARY_PATH=./target/native/x86_64-linux/bin:/usr/local/cuda/lib64 \
//      valgrind --leak-check=full ./standalone_test

#include "OptiXWrapper.h"
#include <iostream>
#include <vector>

int main() {
    std::cout << "=== OptiX Standalone Memory Leak Test ===" << std::endl;

    try {
        // Create wrapper
        std::cout << "Creating OptiXWrapper..." << std::endl;
        OptiXWrapper wrapper;

        // Initialize
        std::cout << "Initializing..." << std::endl;
        if (!wrapper.initialize()) {
            std::cerr << "Failed to initialize OptiX" << std::endl;
            return 1;
        }

        // Configure scene
        std::cout << "Configuring scene..." << std::endl;
        wrapper.setSphere(0.0f, 0.0f, 0.0f, 1.5f);

        float eye[] = {0.0f, 0.0f, 3.0f};
        float lookAt[] = {0.0f, 0.0f, 0.0f};
        float up[] = {0.0f, 1.0f, 0.0f};
        wrapper.setCamera(eye, lookAt, up, 60.0f);

        float lightDir[] = {0.57735f, 0.57735f, -0.57735f};
        wrapper.setLight(lightDir, 1.0f);

        // Render
        std::cout << "Rendering..." << std::endl;
        int width = 800;
        int height = 600;
        std::vector<uint8_t> output(width * height * 4); // RGBA
        wrapper.render(width, height, output.data());

        std::cout << "Rendered " << output.size() << " bytes" << std::endl;

        // Render again to test reuse
        std::cout << "Rendering second frame..." << std::endl;
        wrapper.render(width, height, output.data());

        // Cleanup happens automatically via RAII
        std::cout << "Cleaning up..." << std::endl;

    } catch (const std::exception& e) {
        std::cerr << "Exception: " << e.what() << std::endl;
        return 1;
    }

    std::cout << "=== Test completed successfully ===" << std::endl;
    return 0;
}
