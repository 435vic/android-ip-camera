{
  description = "Android IP Camera development toolchain";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      makeEnvironment = withEmulator:
        let
          buildToolsVersion = "36.0.0";
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "37.0" ];
            buildToolsVersions = [ buildToolsVersion ];
            includeEmulator = withEmulator;
            includeSystemImages = withEmulator;
            systemImageTypes = [ "google_apis" ];
            abiVersions = [ "x86_64" ];
            includeNDK = false;
            includeCmake = false;
            toolsVersion = null;
          };
          tools = [
            pkgs.jdk21
            android.androidsdk
            pkgs.git
            pkgs.curl
            pkgs.android-tools
            pkgs.tailcat
            pkgs.unzip
            pkgs.zip
            pkgs.openssl
            pkgs.openssh
            pkgs.python3
            pkgs.jq
            pkgs.ffmpeg
            pkgs.imagemagick
            pkgs.apksigcopier
            pkgs.shellcheck
          ];
          profile = pkgs.buildEnv {
            name = "android-ip-camera-dev";
            paths = tools ++ [ activation ];
          };
          environment = {
            JAVA_HOME = "${pkgs.jdk21}";
            ANDROID_HOME = "${android.androidsdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${android.androidsdk}/libexec/android-sdk";
            # Use the patched Nix binary rather than AGP's downloaded ELF binary.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${android.androidsdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";
          };
          # Keep exports rooted by the installed profile, even after Nix GC.
          activation = pkgs.writeTextDir "share/android-ip-camera/env.sh"
            (pkgs.lib.concatStringsSep "\n"
              (pkgs.lib.mapAttrsToList (name: value:
                "export ${name}=${pkgs.lib.escapeShellArg value}") environment) + ''

              export PATH="$ANDROID_HOME/build-tools/${buildToolsVersion}:$PATH"
            '');
        in {
          inherit profile;
          shell = pkgs.mkShell {
            packages = tools;
            shellHook = "source ${activation}/share/android-ip-camera/env.sh";
          };
        };
      build = makeEnvironment false;
      emulator = makeEnvironment true;
    in {
      packages.${system}.default = build.profile;
      devShells.${system} = {
        default = build.shell;
        emulator = emulator.shell;
      };
    };
}
