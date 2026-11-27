local items = require("autotracking.item_mapping")
local locations = require("location_mapping")
local power = "@Workshop/Power/Power Restored"

assert(Tracker:AddItems("items/equipment.json"))
assert(Tracker:AddMaps("maps/yard.json"))
assert(Tracker:AddMaps("maps/workshop.json"))
assert(Tracker:AddLocations("locations/yard.json"))
assert(Tracker:AddLocations("locations/workshop.json"))
assert(Tracker:AddLocations("locations/workshop/survey.json"))
assert(Tracker:AddClasses("layouts/classes.json"))
assert(Tracker:AddLayouts("layouts/tracker.json"))
assert(Tracker:AddLayouts("layouts/settings.json"))
assert(ScriptHost:LoadScript("scripts/survey.lua"))

Archipelago:AddClearHandler("station reset", function()
    local previous_bulk_update = Tracker.BulkUpdate
    Tracker.BulkUpdate = Tracker.AllowDeferredLogicUpdate
    Tracker:FindObjectForCode("fuse").Active = false
    Tracker:FindObjectForCode("receiver").CurrentStage = 0
    Tracker:FindObjectForCode("cell").AcquiredCount = 0
    local transmitter = Tracker:FindObjectForCode("transmitter")
    transmitter.CurrentStage = 0
    transmitter.Active = false
    Tracker:FindObjectForCode("calibrated").Active = false
    Tracker:FindObjectForCode(power).Highlight = Highlight.None
    for _, codes in pairs(locations) do
        for _, code in ipairs(codes) do
            Tracker:FindObjectForCode(code).AvailableChestCount = 1
        end
    end
    Tracker.BulkUpdate = previous_bulk_update
    if Archipelago.PlayerNumber > 0 then
        local keys = {"SignalStation:" .. Archipelago.PlayerNumber .. ":calibrated"}
        Archipelago:Get(keys)
        Archipelago:SetNotify(keys)
    end
end)

Archipelago:AddItemHandler("station equipment", function(_, item_id)
    local codes = items[item_id]
    if not codes then return end
    local item = Tracker:FindObjectForCode(codes[1])
    if item.Type == "progressive" then
        item.CurrentStage = math.min(item.CurrentStage + 1, 2)
    elseif item.Type == "consumable" then
        item.AcquiredCount = item.AcquiredCount + item.Increment
    else
        item.Active = true
    end
end)

Archipelago:AddLocationHandler("station checks", function(location_id)
    for _, code in ipairs(locations[location_id] or {}) do
        Tracker:FindObjectForCode(code).AvailableChestCount = 0
    end
    if location_id == 920102 then
        local transmitter = Tracker:FindObjectForCode("transmitter")
        transmitter.CurrentStage = 1
        transmitter.Active = true
    end
end)

local function update_calibration(key, value)
    if key == "SignalStation:" .. Archipelago.PlayerNumber .. ":calibrated" then
        Tracker:FindObjectForCode("calibrated").Active = value == true
        Tracker:FindObjectForCode(power).Highlight = value == true and Highlight.Priority or Highlight.None
    end
end

Archipelago:AddRetrievedHandler("station calibration", update_calibration)
Archipelago:AddSetReplyHandler("station calibration updates", update_calibration)
