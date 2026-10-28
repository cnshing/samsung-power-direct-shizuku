{
  description = "Power Direct Android SDK for Devbox";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";

  outputs = { nixpkgs, ... }:
    let
      systems = [ "x86_64-linux" "x86_64-darwin" "aarch64-darwin" ];
    in
    {
      packages = nixpkgs.lib.genAttrs systems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "36" ];
            buildToolsVersions = [ "35.0.0" ];
            includeNDK = true;
            ndkVersions = [ "27.1.12297006" ];
            includeCmake = true;
            cmakeVersions = [ "3.22.1" ];
            toolsVersion = null;
            includeEmulator = false;
            includeSystemImages = false;
          };
        in
        {
          default = android.androidsdk;
        });
    };
}
