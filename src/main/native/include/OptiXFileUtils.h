#pragma once

#include <vector>
#include <string>
#include <fstream>
#include <sstream>
#include <stdexcept>

namespace optix_utils {

// Read PTX file from first available location in search paths
// Throws runtime_error with detailed diagnostic if file not found
inline std::string readPTXFile(const std::vector<std::string>& search_paths) {
    std::ostringstream error_log;
    error_log << "Failed to find PTX file. Tried locations:\n";

    for (const auto& path : search_paths) {
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        if (file.is_open()) {
            std::streamsize size = file.tellg();
            file.seekg(0, std::ios::beg);

            std::string content(size, '\0');
            if (file.read(&content[0], size)) {
                return content;
            } else {
                error_log << "  - " << path << " (read failed)\n";
            }
        } else {
            error_log << "  - " << path << " (not found)\n";
        }
    }

    throw std::runtime_error(error_log.str());
}

}  // namespace optix_utils
