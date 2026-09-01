-- The contract test stays synthetic so PackSanity never redistributes another author's pack.
assert(dofile == nil and loadfile == nil)
assert(PackSanityIsSandboxed('package.loadlib'))

PackSanityRegisterPackFile('data/value.txt', 'PackSanity\n')
local file = assert(io.open('./data/value.txt', 'r'))
assert(file:read('*l') == 'PackSanity')
assert(io.open('../outside.txt', 'r') == nil)
assert(io.open('data/value.txt', 'w') == nil)

PackSanitySeedItem('sword', 'toggle', 1, false, true, 0, 0, 0, 1, 1, '', {'sword'}, {}, false, nil, nil, nil)
assert(Tracker:FindObjectForCode('sword').Name == 'sword')
PackSanitySeedItem('case-sensitive-source', 'static', 1, false, false, 0, 0, 0, 1, 1, '', {'lower_case'}, {}, false, nil, nil, nil)
assert(Tracker:FindObjectForCode('LOWER_CASE').Name == 'case-sensitive-source')
assert(Tracker:ProviderCountForCode('LOWER_CASE') == 1)

local failing_item = Tracker:CreateLuaItem({ Name = 'failing-observer' })
failing_item.PropertyChangedFunc = function() error('conformance observer failure') end
failing_item:Set('value', 1)
assert(PackSanityDrainCallbackErrors():find('conformance observer failure', 1, true))

function ConformanceFailingRule() error('conformance rule failure') end
assert(PackSanityEvaluateRule('ConformanceFailingRule') == 0)
local rule_field_separator = string.char(28)
local failed_rule = 'lua' .. rule_field_separator .. 'failed-rule' .. rule_field_separator .. 'ConformanceFailingRule'
assert(PackSanityRuleValues(failed_rule) == 'failed-rule' .. rule_field_separator .. '0')
assert(PackSanityDrainCallbackErrors():find('conformance rule failure', 1, true))
function ConformanceTrueRule() return true end
local true_rule = 'lua' .. rule_field_separator .. 'true-rule' .. rule_field_separator .. 'ConformanceTrueRule'
assert(PackSanityRuleValues(true_rule) == 'true-rule' .. rule_field_separator .. '1')

local watched, framed, location_changed = 0, 0, 0
assert(ScriptHost:AddWatchForCode('conformance-watch', 'sword', function() watched = watched + 1 end))
assert(ScriptHost:AddOnFrameHandler('conformance-frame', function() framed = framed + 1 end))
assert(PackSanityChangeItemState('sword', 'primary'))
assert(watched >= 1)
PackSanityRunFrame()
assert(framed == 1)

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

Archipelago.__allows_location_checks = true
Archipelago.__allows_location_scouts = true
assert(Archipelago:LocationChecks({1, 2}))
assert(Archipelago:LocationScouts({3}, true))
assert(Archipelago:StatusUpdate(Archipelago.ClientStatus.READY))
local commands = PackSanityDrainArchipelagoCommands()
assert(commands:find('checks\30' .. '1,2', 1, true))
assert(commands:find('scouts\30' .. '1,3', 1, true))
assert(commands:find('status\30' .. '10', 1, true))
