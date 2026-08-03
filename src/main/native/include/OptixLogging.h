#ifndef OPTIX_LOGGING_H
#define OPTIX_LOGGING_H

#include <cstdlib>
#include <iostream>
#include <ostream>
#include <string>

// F12 (Sprint 35 Task 3.4): env-gated native logging.
//
// Production native code writes through OPTIX_LOG(level) instead of raw std::cerr / std::cout,
// so a released optix-jni does not spam the host application's console. Verbosity is read once
// from the OPTIX_LOG_LEVEL environment variable (NONE|ERROR|WARN|INFO|DEBUG, or 0..4; default
// ERROR): error diagnostics stay visible, INFO/DEBUG progress chatter is silent unless opted in.
//
// This header is the SINGLE allowed sink for raw stream writes. The no-raw-writes fitness gate
// (scripts/check-native-logging.sh, run by the pre-push hook and CI) fails if std::cerr / cout /
// printf appears in any other production native translation unit.
namespace optix::log {

enum class Level : int { NONE = 0, ERROR = 1, WARN = 2, INFO = 3, DEBUG = 4 };

// Parsed once from OPTIX_LOG_LEVEL on first use; unset or unrecognized → ERROR.
inline Level currentLevel() {
  static const Level level = [] {
    const char* raw = std::getenv("OPTIX_LOG_LEVEL");
    if (raw == nullptr) return Level::ERROR;
    const std::string value(raw);
    if (value == "NONE"  || value == "0") return Level::NONE;
    if (value == "ERROR" || value == "1") return Level::ERROR;
    if (value == "WARN"  || value == "2") return Level::WARN;
    if (value == "INFO"  || value == "3") return Level::INFO;
    if (value == "DEBUG" || value == "4") return Level::DEBUG;
    return Level::ERROR;
  }();
  return level;
}

// Errors go to stderr (as before); INFO/DEBUG progress goes to stdout (as before).
inline std::ostream& sink(Level level) {
  return level <= Level::ERROR ? std::cerr : std::cout;  // NOLINT: the one permitted raw sink
}

}  // namespace optix::log

// Stream-style and dangling-else-safe: OPTIX_LOG(ERROR) << "msg " << x << std::endl;
// The right-hand side is evaluated and written only when `lvl` is enabled at the current level.
#define OPTIX_LOG(lvl)                                                \
  if (::optix::log::Level::lvl > ::optix::log::currentLevel()) {      \
  } else                                                             \
    ::optix::log::sink(::optix::log::Level::lvl)

#endif  // OPTIX_LOGGING_H
