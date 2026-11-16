cmake_minimum_required(VERSION 3.22.1)
project(voxtitle_native LANGUAGES C CXX)

add_library(bionic_compat23 SHARED bionic_compat23.c)

target_compile_options(bionic_compat23 PRIVATE
    -U_FORTIFY_SOURCE
    -D_FORTIFY_SOURCE=0
    -O2
    -fvisibility=default
)

target_link_options(bionic_compat23 PRIVATE
    -Wl,-z,global
    -Wl,-z,max-page-size=16384
)

target_link_libraries(bionic_compat23 dl)

add_library(vlc_audio_bridge SHARED vlc_audio_bridge.cpp)

target_compile_options(vlc_audio_bridge PRIVATE
    -O2
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
)

target_link_options(vlc_audio_bridge PRIVATE
    -Wl,-z,max-page-size=16384
)

target_link_libraries(vlc_audio_bridge dl log m)

add_library(mpv_player_bridge SHARED mpv_player_bridge.cpp)

target_compile_options(mpv_player_bridge PRIVATE
    -O2
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
)

target_link_options(mpv_player_bridge PRIVATE
    -Wl,-z,max-page-size=16384
)

target_link_libraries(mpv_player_bridge dl log m)
