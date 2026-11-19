// Test-only observation hooks. Never installed globally.
import fs from 'node:fs';
import path from 'node:path';
export default function(pi: any) {
  const root=path.resolve(process.env.BRIDGE_TEST_ROOT!);
  const log=(data:any)=>fs.appendFileSync(path.join(root,'evidence/tui-observations.jsonl'),JSON.stringify(data)+'\n');
  pi.on('session_start',(_:any,ctx:any)=>{const file=path.join(root,'evidence/original-session.txt');if(!fs.existsSync(file))fs.writeFileSync(file,ctx.sessionManager.getSessionFile());});
  pi.on('input',(event:any)=>{log({event:'input',...event});});
  pi.on('agent_start',(_:any,ctx:any)=>log({event:'agent_start',editor:ctx.ui.getEditorText()}));
  pi.on('agent_settled',(_:any,ctx:any)=>log({event:'agent_settled',idle:ctx.isIdle(),editor:ctx.ui.getEditorText()}));
  pi.registerCommand('bridge-test-fork',{description:'Test-only fork',handler:async(_:any,ctx:any)=>{const entry=ctx.sessionManager.getBranch().find((e:any)=>e.type==='message'&&e.message.role==='user');if(entry)await ctx.fork(entry.id,{position:'at'});}});
  pi.registerCommand('bridge-test-resume',{description:'Test-only resume',handler:async(_:any,ctx:any)=>{await ctx.switchSession(fs.readFileSync(path.join(root,'evidence/original-session.txt'),'utf8'));}});
  pi.registerCommand('bridge-test-confirm',{description:'Test-only dialog',handler:async(_:any,ctx:any)=>{const result=await ctx.ui.confirm('Bridge test confirmation','Choose Yes to verify the terminal fallback');pi.sendMessage({customType:'bridge-test-dialog',content:`Dialog result: ${result}`,display:true});log({event:'dialog',result});}});
}
