print('PackSanity callback fixture')
Tracker:AddItems('items/' .. Tracker.ActiveVariantUID .. '.json')
Tracker:AddLocations('locations/main.json')
Archipelago:AddItemHandler('fixture-item', function(index, id, name, player)
    if id == 1001 then Tracker:FindObjectForCode('sword').Active = true end
end)
Archipelago:AddRetrievedHandler('fixture-hint', function(key, value)
    if key == '_read_hints_0_1' then
        for _, hint in ipairs(value) do
            if hint.finding_player == Archipelago.PlayerNumber and hint.location == 2001 then Tracker:FindObjectForCode('@Area/Chest').Highlight = 1 end
        end
    end
end)
