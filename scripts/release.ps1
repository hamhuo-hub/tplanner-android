# Release script: tagging = release.
# The git tag is the single source of truth for versioning; the root build.gradle.kts
# derives versionName via `git describe` at build time.
#
# Only a tag that exists on origin counts as a release: build.gradle.kts requires the exact
# tag to be pushed before it will report a non-dev versionName. So a tag created here but
# never pushed produces `X.Y.Z-dev+tag-not-pushed` — use -Push, or push afterwards.
#
# Usage:
#   .\scripts\release.ps1 6.0.2          # create tag mobile_6.0.2 locally
#   .\scripts\release.ps1 6.0.2 -Push    # create tag and push to origin
#
# After tagging and pushing, check the version that will be built: .\gradlew.bat printVersion
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [switch]$Push
)

$ErrorActionPreference = 'Stop'

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Version must be x.y.z (e.g. 6.0.2), got: $Version"
}
$newParts = [int[]]($Version -split '\.')
$tag = "mobile_$Version"

# Zero-pad each semver part to 4 digits so that 6.0.10 > 6.0.2 compares correctly.
function Pad([int[]]$parts) {
    ($parts | ForEach-Object { '{0:D4}' -f $_ }) -join ''
}

function Get-AndroidTags([string[]]$names) {
    $names |
        Where-Object { $_ -match '^(mobile_|PUKEKO_)\d+\.\d+\.\d+$' } |
        Sort-Object -Descending { Pad ([int[]](($_ -replace '^(mobile_|PUKEKO_)', '') -split '\.')) }
}

# 1. Working tree must be clean, otherwise the tag would not include pending changes.
if (git status --porcelain) {
    throw 'Working tree is dirty; commit or discard changes before tagging.'
}

# 2. The target tag must not already exist.
if (git tag -l $tag) {
    throw "Tag $tag already exists."
}

# 3. Baseline = latest Android release tag that is actually ON THE REMOTE. Desktop's v* tags
#    must not leak in here, and an unpushed local tag is not a release.
$remoteTags = (git ls-remote --tags --refs origin) |
    ForEach-Object { ($_ -split 'refs/tags/')[1] } |
    Where-Object { $_ }
$latestPushed = Get-AndroidTags $remoteTags | Select-Object -First 1

if ($latestPushed) {
    $latestNum = $latestPushed -replace '^(mobile_|PUKEKO_)', ''
    if ((Pad $newParts) -le (Pad ([int[]]($latestNum -split '\.')))) {
        throw "Version $Version is not higher than the latest pushed tag $latestPushed; bump the version."
    }
}
else {
    Write-Warning 'No Android release tag found on origin; this will be the first pushed release tag.'
}

# 3b. A higher LOCAL tag that was never pushed means an earlier release is missing upstream.
$latestLocal = Get-AndroidTags (git tag -l) | Select-Object -First 1
if ($latestLocal -and $latestLocal -ne $latestPushed) {
    Write-Warning "Local tag $latestLocal is not on origin; push it too or the earlier release stays unpublished."
}

# 4. Confirm the current branch.
$branch = git rev-parse --abbrev-ref HEAD
if ($branch -ne 'mobile_andorid') {
    Write-Warning "Current branch is $branch (releases are usually tagged on mobile_andorid)."
}

# 5. Create an annotated tag (git describe prefers annotated tags).
git tag -a $tag -m "Release $tag"
$code = $newParts[0] * 1000 + $newParts[1] * 100 + $newParts[2]
Write-Host "Tag created: $tag (versionCode $code)"

# 6. Push. Until the tag is on origin, versionName degrades to -dev; say so plainly.
if ($Push) {
    git push origin $tag
    Write-Host "Pushed $tag to origin. Next build: versionName = $Version , versionCode = $code"
}
else {
    Write-Warning "Not pushed. Builds at this commit will report versionName = $Version-dev+tag-not-pushed."
    Write-Host "Push it with: git push origin $tag"
}
