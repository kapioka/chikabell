$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-signing.ps1')
Register-LocalSigningProfile -App chikabell -Repository (Split-Path $PSScriptRoot -Parent) -DefaultAlias 'release'
