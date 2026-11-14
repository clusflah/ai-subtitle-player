cmake_minimum_required(VERSION 3.22.1)
project(bionic_compat23 LANGUAGES C)

add_library(bionic_compat23 SHARED bionic_compat23.c)

target_compile_options(bionic_compat23 PRIVATE
    -U_FORTIFY_SOURCE
    -D_FORTIFY_SOURCE=0
    -O2
    -fvisibility=default
)

target_link_options(bionic_compat23 PRIVATE
    -Wl,-z,global
)

target_link_libraries(bionic_compat23 dl)
