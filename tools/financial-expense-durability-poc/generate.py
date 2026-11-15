"""Compose an isolated expense proof with a read-only copy of the existing 9C profile rules."""
from pathlib import Path
root=Path(__file__).resolve().parents[2]
source=(root/'firestore/firestore.rules').read_text()
profiles=source[source.index('    function owner(uid)'):source.index('    function groupShape(p)')]
prefix="""rules_version = '2';
// EXPENSE-ONLY DISPOSABLE EMULATOR POC. Never deploy.
service cloud.firestore {
 match /databases/{database}/documents {
  function signedIn() { return request.auth!=null && request.auth.token.aud=='demo-splitease-expense-durability'; }
"""
out=prefix+profiles+(Path(__file__).with_name('expense-body.rules')).read_text()+'\n }\n}\n'
Path(__file__).with_name('expense.rules').write_text(out)
print('Generated isolated Rules bytes:',len(out.encode()))
