export function normalizeVersion(version: string): string {
  return version.trim().replace(/^v/, '');
}

type AntVersionParts = {
  major: number;
  minor: number;
  patch: number;
};

function parseAntVersion(version: string): AntVersionParts | null {
  const current = normalizeVersion(version);
  const parts = current.match(/^(\d+)\.(\d+)\.([0-9a-fA-F]+)\.(\d+)$/);
  if (!parts) return null;

  return {
    major: Number(parts[1]),
    minor: Number(parts[2]),
    patch: Number(parts[4]),
  };
}

function compareAntRelease(a: AntVersionParts, b: AntVersionParts): number {
  if (a.major !== b.major) return a.major < b.major ? -1 : 1;
  if (a.minor !== b.minor) return a.minor < b.minor ? -1 : 1;
  if (a.patch !== b.patch) return a.patch < b.patch ? -1 : 1;
  return 0;
}

export function isOutOfDate(
  current: string,
  latest: string | undefined,
  latestSha: string | undefined,
  currentBuildTimestamp?: number,
  latestBuildTimestamp?: number,
): boolean | null {
  const normalizedCurrent = normalizeVersion(current);
  if (!normalizedCurrent) return null;
  if (latest) {
    const normalizedLatest = normalizeVersion(latest);
    if (normalizedCurrent === normalizedLatest) return false;

    const currentParts = parseAntVersion(normalizedCurrent);
    const latestParts = parseAntVersion(normalizedLatest);

    if (currentParts && latestParts) {
      const releaseOrder = compareAntRelease(currentParts, latestParts);
      if (releaseOrder !== 0) return releaseOrder < 0;
      if (typeof currentBuildTimestamp === 'number' && typeof latestBuildTimestamp === 'number') {
        return currentBuildTimestamp < latestBuildTimestamp;
      }
      return null;
    }

    return true;
  }

  const currentHash =
    normalizedCurrent.match(/^\d+\.\d+\.([0-9a-fA-F]+)\.\d+$/)?.[1].toLowerCase() ||
    normalizedCurrent.match(/-g([0-9a-fA-F]+)$/)?.[1].toLowerCase();
  if (!currentHash || !latestSha) return null;
  return !latestSha.toLowerCase().startsWith(currentHash);
}
