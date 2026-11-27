local survey = ScriptHost:CreateLuaItem()
survey.Name = "Station Survey"
survey.Icon = ImageReference:FromPackRelativePath("images/signal.png")
survey.PotentialCodes = {"survey_scanned", "survey_link"}
survey.ProvidesCodeFunc = function(item, code)
    return item:Get(code) and 1 or 0
end

local pending_link

Archipelago:AddClearHandler("reset survey", function()
    pending_link = nil
    survey:Set("survey_scanned", false)
    survey:Set("survey_link", false)
end)

Archipelago:AddScoutHandler("survey receiver", function(location, _, item, _, player)
    if location == 920101 and item == 920002 and player == Archipelago.PlayerNumber then
        survey:Set("survey_scanned", true)
    end
end)

Archipelago:AddBouncedHandler("survey relay message", function(packet)
    for _, tag in ipairs(packet.tags or {}) do
        if tag == "SignalStationSurvey" and packet.data and packet.data.slot == Archipelago.PlayerNumber then
            pending_link = packet.data.online == true
        end
    end
end)

ScriptHost:AddOnFrameHandler("apply survey relay status", function()
    if pending_link ~= nil then
        survey:Set("survey_link", pending_link)
        pending_link = nil
    end
end)

function StationSurvey(bands)
    if bands == "low,high" and survey:Get("survey_link") then
        return AccessibilityLevel.Normal
    end
    return AccessibilityLevel.None
end

local function update_survey_highlight()
    local section = Tracker:FindObjectForCode("@Survey Terminal/")
    if not survey:Get("survey_scanned") then
        section.Highlight = Highlight.None
    elseif StationSurvey("low,high") == AccessibilityLevel.Normal then
        section.Highlight = Highlight.Priority
    else
        section.Highlight = Highlight.Unspecified
    end
end

ScriptHost:AddWatchForCode("survey progress", "survey_scanned", update_survey_highlight)
ScriptHost:AddWatchForCode("survey link", "survey_link", update_survey_highlight)
