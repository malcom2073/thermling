[app]

title = Thermling
project_dir = .
input_file = main.py
exec_directory = dist
project_file =
icon = src/thermling/assets/icon.png

[python]

packages = Nuitka==4.1.1
android_packages = buildozer==1.6.0,cython==0.29.33

[qt]

qml_files =
excluded_qml_plugins =
modules = Core,Gui,Widgets,Network,Bluetooth,Svg
plugins =

[android]

wheel_pyside =
wheel_shiboken =
plugins =

[buildozer]

mode = debug
recipe_dir =
jars_dir =
ndk_path =
sdk_path =
local_libs =
arch = aarch64
