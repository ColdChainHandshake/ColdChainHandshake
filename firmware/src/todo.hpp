// Marks a function the owner hasn't written yet. Each call throws, so its tests fail with a message naming the
// step in firmware/WALKTHROUGH.md. Delete this file once nothing includes it.
#pragma once

#include <stdexcept>
#include <string>

namespace cch {

[[noreturn]] inline void todo(const char* what) {
    throw std::logic_error(std::string("TODO(owner): ") + what);
}

}  // namespace cch
