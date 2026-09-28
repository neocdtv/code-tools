#!/bin/bash

classes=(
    "RechnungData"
    "BuchungExportData"
    "BuchungExportMietvertragData"
    "RechnungDTO"
    "RechnungspositionDTO"
    "RechnungDetailPositionDTO"
    "Mietvertrag"
    "Firma"
    "User"
    "RechnungslaufQueueEntry"
    "RechnungsFreigabe"
    "RechnPos"
    "RechnDetailPos"
    "Money"
    "TimeRange"
    "JsonRechnPosMiete"
    "BuchungDao"
    "MietvertragDao"
    "RechnungDao"
    "RechnungsfreigabenDao"
    "RepositoryRechnungRepository"
    "RepositoryRechnungslaeufeImpl"
    "BuchungPreLoader"
    "ServiceFahrzeugImpl"
    "ServiceFirmaImpl"
    "ServiceRechnPosImpl"
    "ServiceRechnungslaeufeImpl"
    "ServiceUserImpl"
    "RechnungExporter"
    "BuchungTextBuilder"
    "BuchungMieteTextBuilder"
    "VertragsAbstractionFactory"
    "EntityManager"
    "CWLogger"
    "Tools"
    "RechnungTools"
    "CWAuthorizationUtil"
)

found=()
not_found=()

for class in "${classes[@]}"; do
    results=$(find . -name "*$class*" 2>/dev/null)
    if [ -z "$results" ]; then
        not_found+=("$class")
    else
        found+=("$class")
    fi
done

echo "=== FOUND CLASSES ==="
for item in "${found[@]}"; do
    echo "- $item"
    find . -name "*$item*" 2>/dev/null | sed 's/^/    /'
done

echo ""
echo "=== NOT FOUND CLASSES ==="
for item in "${not_found[@]}"; do
    echo "- $item"
done
