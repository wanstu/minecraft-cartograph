#!/usr/bin/env python3
"""Native UI and tile-progress structural regression guard.
No claim of a live game screenshot/latency test.
"""
from pathlib import Path
code=Path(__file__).resolve().parents[1]/'java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph'
ui=(code/'AtlasScreen.java').read_text()
server=(code/'AtlasMapServer.java').read_text()
client=(code/'CartographClient.java').read_text()
actions=(code/'AtlasActions.java').read_text()
for token in (
    'g.pose().translate(0, 0, 300);',
    'renderDialog(g);',
    'super.render(g, mouseX, mouseY, partial);',
    'setFocused(name); name.setFocused(true);',
    'materialNotice = text;',
    'requestRefill(index)',
    'annotationPending = true;',
    'if (annotationPending && (dialog == Dialog.MARKER || dialog == Dialog.REGION))',
    'materialNotice = "正在提交"',
    'addRenderableWidget(box);',
    'requestStartedAtTick',
):
    assert token in ui, 'Missing field/feedback contract: '+token
modal=ui.split('if (dialog == Dialog.NONE) {',1)[1].split('if (!message.isEmpty()',1)[0]
assert modal.index('g.pose().translate(0, 0, 300);') < modal.index('renderDialog(g);') < modal.index('super.render(g, mouseX, mouseY, partial);') < modal.index('g.pose().popPose();')
assert ui.count('super.render(g, mouseX, mouseY, partial);') == 1, 'EditBoxes rendered behind dialog or twice'
panel=ui.split('case ATLAS -> {',1)[1].split('case MARKERS -> {',1)[0]
assert 'sendAddPage' not in panel, 'Extra expand button returned to resource panel'
materials=ui.split('private void materials(',1)[1].split('private String refillAmount(',1)[0]
assert 'CartographClient.sendAddPage()' in materials
for token in (
    'boolean milestone = done;',
    'MAX_SURVEY_NANOS_PER_TICK',
    'index + 13',
    'Map tile still awaiting saved chunk:',
):
    assert token in server, 'Missing survey-progress contract: '+token
assert 'if (page.complete()) {' in client and 'IN_FLIGHT.remove(currentRequest)' in client
assert 'MAX_PARALLEL_PAGES = 4' in client
assert 'if (changedHeights && page.complete()' in client
assert 'case 6 -> "空地图投入成功' in actions
print('PASS modal EditBox Z order, feedback, color/marker save, map controls, complete-only tile updates')
