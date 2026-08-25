{ lib, gitRev ? "unknown" }:

let
  parseLine = line:
    let
      parts = lib.splitString "=" line;
    in {
      name = lib.trim (lib.elemAt parts 0);
      value = lib.trim (lib.elemAt parts 1);
    };

  versionParts = builtins.listToAttrs (
    map parseLine (
      builtins.filter
        (line:
          let trimmed = lib.trim line;
          in trimmed != "" && !(lib.hasPrefix "#" trimmed))
        (lib.splitString "\n" (lib.fileContents ../../meson/ant.version))
    )
  );

  shortGitRev = if gitRev == "unknown" then gitRev else builtins.substring 0 8 gitRev;
in
"${versionParts.major}.${versionParts.minor}.${shortGitRev}.${versionParts.patch}"
