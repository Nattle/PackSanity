-- The contract test stays synthetic so PackSanity never redistributes another author's pack.
assert(dofile == nil and loadfile == nil)
assert(PackSanityIsSandboxed('package.loadlib'))

PackSanityRegisterPackFile('data/value.txt', 'PackSanity\n')
local file = assert(io.open('./data/value.txt', 'r'))
assert(file:read('*l') == 'PackSanity')
assert(io.open('../outside.txt', 'r') == nil)
assert(io.open('data/value.txt', 'w') == nil)

do
    -- Pseudoregalia uses the upstream long-form PackIO reads for its rule data.
    PackSanityRegisterPackFile('data/readings.txt', 'first\r\n27 tail')
    local readings = assert(io.open('data/readings.txt'))
    assert(readings:read('*line') == 'first')
    assert(readings:read('*number') == 27)
    local position = readings:seek()
    assert(readings:read('*all') == ' tail' and readings:seek() == position)
    assert(readings:read('*a') == ' tail' and readings:read('*all') == '')
    readings:seek('set')
    local lines = readings:lines('*line')
    assert(lines() == 'first' and lines() == '27 tail' and lines() == nil)
end

PackSanitySeedItem('sword', 'toggle', 1, false, true, 0, 0, 0, 1, 1, '', {'sword'}, {}, false, nil, nil, nil)
assert(Tracker:FindObjectForCode('sword').Name == 'sword')
do
    -- The upstream JsonItem setter treats only integer values as signed flags.
    local sword = Tracker:FindObjectForCode('sword')
    for _, sample in ipairs({{0, false}, {-1, false}, {2, true}, {0.0, true}, {'', true}}) do
        sword.Active = sample[1]
        assert(sword.Active == sample[2])
        assert(Tracker:ProviderCountForCode('sword') == (sample[2] and 1 or 0))
    end
    sword.Active = nil
    assert(not sword.Active)
end
PackSanitySeedItem('case-sensitive-source', 'static', 1, false, false, 0, 0, 0, 1, 1, '', {'lower_case'}, {}, false, nil, nil, nil)
assert(Tracker:FindObjectForCode('LOWER_CASE').Name == 'case-sensitive-source')
assert(Tracker:ProviderCountForCode('LOWER_CASE') == 1)

local failing_item = Tracker:CreateLuaItem({ Name = 'failing-observer' })
failing_item.PropertyChangedFunc = function() error('conformance observer failure') end
failing_item:Set('value', 1)
assert(PackSanityDrainCallbackErrors():find('conformance observer failure', 1, true))

local custom = Tracker:CreateLuaItem({ PotentialCodes = {'key'} })
local code_changes = 0
ScriptHost:AddWatchForCode('custom-watch', 'key', function() error('replaced watch fired') end)
ScriptHost:AddWatchForCode('custom-watch', 'other', function() error('unrelated watch fired') end)
ScriptHost:AddWatchForCode('active-watch', 'key', function(code)
    assert(code == 'key' and custom:Get('amount') == 1)
    code_changes = code_changes + 1
end)
custom:Set('amount', 1)
custom:Set('amount', 1)
assert(code_changes == 1)
assert(PackSanityDrainCallbackErrors() == '')

do
    local survey = ScriptHost:CreateLuaItem()
    survey.Name = 'Survey\30Receiver'
    survey.Icon = ImageReference:FromPackRelativePath('icons/receiver.png')
    survey.PotentialCodes = {'receiver'}
    survey.ProvidesCodeFunc = function(item) return item:Get('active') and 2 or 0 end
    Archipelago:AddRetrievedHandler('survey', function(_, value) survey:Set('active', value) end)
    Archipelago:AddClearHandler('survey', function() survey:Set('active', false) end)

    assert(Tracker:ProviderCountForCode('receiver') == 0)
    PackSanityDispatchRetrieved('survey', true)
    -- A provider registered after creation must work before an object lookup warms any index.
    assert(Tracker:ProviderCountForCode('receiver') == 2)
    assert(PackSanityHostedItems('receiver') ==
        table.concat({'receiver', 'Survey Receiver', 'icons/receiver.png', '', '', '2'}, '\30'))
    survey.PotentialCodes = {'receiver_online'}
    assert(Tracker:ProviderCountForCode('receiver') == 0)
    assert(Tracker:ProviderCountForCode('receiver_online') == 2)
    PackSanityDispatchClear({})
    assert(Tracker:ProviderCountForCode('receiver_online') == 0)
end

function ConformanceFailingRule() error('conformance rule failure') end
assert(PackSanityEvaluateRule('ConformanceFailingRule') == 0)
local rule_field_separator = string.char(28)
local failed_rule = 'lua' .. rule_field_separator .. 'failed-rule' .. rule_field_separator .. 'ConformanceFailingRule'
assert(PackSanityRuleValues(failed_rule) == 'failed-rule' .. rule_field_separator .. '0')
assert(PackSanityDrainCallbackErrors():find('conformance rule failure', 1, true))
function ConformanceTrueRule() return true end
local true_rule = 'lua' .. rule_field_separator .. 'true-rule' .. rule_field_separator .. 'ConformanceTrueRule'
assert(PackSanityRuleValues(true_rule) == 'true-rule' .. rule_field_separator .. '1')

local watched, elapsed_frames, location_changed = 0, {}, 0
assert(ScriptHost:AddWatchForCode('conformance-watch', 'sword', function() watched = watched + 1 end))
assert(ScriptHost:AddOnFrameHandler('conformance-frame', function(elapsed)
    elapsed_frames[#elapsed_frames + 1] = elapsed
end))
assert(PackSanityChangeItemState('sword', 'primary'))
assert(watched >= 1)
PackSanityRunFrame()
PackSanityRunFrame(1.25)
assert(#elapsed_frames == 2 and elapsed_frames[1] == 0 and elapsed_frames[2] == 1.25)
assert(ScriptHost:RemoveOnFrameHandler('conformance-frame'))

PackSanitySeedLocation('@test/location', 1, {}, false, {}, AccessibilityLevel.Normal)
assert(ScriptHost:AddOnLocationSectionChangedHandler('conformance-location', function() location_changed = location_changed + 1 end))
assert(PackSanityChangeLocationSectionState('@test/location', 'primary'))
assert(location_changed == 1)

PackSanitySeedLocation('@test/default-accessibility', 1, {}, false, {})
local default_accessibility = Tracker:FindObjectForCode('@test/default-accessibility')
assert(default_accessibility.AccessibilityLevel == AccessibilityLevel.None)
local state_field_separator = string.char(30)
PackSanitySetLocationAccessibility('@test/default-accessibility' .. state_field_separator .. AccessibilityLevel.Normal)
assert(default_accessibility.AccessibilityLevel == AccessibilityLevel.Normal)
default_accessibility.AccessibilityLevel = AccessibilityLevel.Partial
PackSanitySetLocationAccessibility('@test/default-accessibility' .. state_field_separator .. AccessibilityLevel.Cleared)
assert(default_accessibility.AccessibilityLevel == AccessibilityLevel.Partial)
PackSanityResetTrackerObjects()
assert(default_accessibility.AccessibilityLevel == AccessibilityLevel.None)

assert(Tracker:AddItems('items/example.json'))
assert(PackSanityRegisteredPaths('items') == 'items/example.json')
assert(ScriptHost:RunStringAsync('return 42', {}, function(value) assert(value == 42) end))

PackSanitySetArchipelagoFlags({'ap', 'aphintgame'})
assert(not Archipelago:StatusUpdate(Archipelago.ClientStatus.GOAL))
assert(not Archipelago:LocationChecks({1}))
PackSanitySetArchipelagoFlags({'apmanual'})
assert(not Archipelago:LocationChecks({1, '2'}))
assert(Archipelago:LocationChecks({1, 2}))
assert(Archipelago:LocationScouts({3}, true))
assert(Archipelago:StatusUpdate(Archipelago.ClientStatus.READY))
local commands = PackSanityDrainArchipelagoCommands()
assert(commands:find('checks\30' .. '1,2', 1, true))
assert(commands:find('scouts\30' .. '1,3', 1, true))
assert(commands:find('status\30' .. '10', 1, true))

local large_location_id = 9007199254740991
Archipelago.MissingLocations = {large_location_id}
PackSanityDispatchLocation(large_location_id, 'Check')
PackSanityDispatchLocation(large_location_id, 'Check')
assert(#Archipelago.MissingLocations == 0 and #Archipelago.CheckedLocations == 1)
assert(Archipelago.CheckedLocations[1] == large_location_id)

do
    -- Portrait of Ruin checks the AP connection through AutoTracker's method form.
    assert(AutoTracker:GetConnectionState('AP') == 0)
    assert(not Archipelago:HintItem('offline'))
    Archipelago.__connected = true
    assert(AutoTracker:GetConnectionState('AP') == 3)
    assert(AutoTracker.GetConnectionState('AP') == 3)
    assert(AutoTracker:GetConnectionState('SNES') == 0)
    assert(not Archipelago:HintLocation({}))
    assert(Archipelago:HintLocation('Café🚀' .. string.char(30, 31, 0)))
    assert(Archipelago:HintItem(902))
    assert(PackSanityDrainArchipelagoCommands() == table.concat({
        'hint_location\30' .. '436166c3a9f09f9a801e1f00', 'hint_item\30' .. '393032',
    }, '\31'))
    assert(Archipelago:HintItem('discard'))
    Archipelago:Get('keep')
    Archipelago.__connected = false
    PackSanityDiscardHintCommands()
    assert(AutoTracker:GetConnectionState('AP') == 0)
    assert(PackSanityDrainArchipelagoCommands() == 'get\30keep')
end

assert(PackSanityDrainCallbackErrors() == '')
