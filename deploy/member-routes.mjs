import {staffRoute, STAFF_SECURITY, staffError, staffBody} from './staff-routes.mjs';
const WEBSITE_UPSTREAM='https://polaris-polaris-hotel.up.railway.app';
const COOKIE='__Host-heaven_session';
const clearCookie=()=>COOKIE+'=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0';
export function websiteCookie(request){
 const values=(request.headers.get('cookie')||'').split(';').map(s=>s.trim()).filter(s=>s.startsWith(COOKIE+'='));
 if(values.length!==1)return '';
 const value=values[0].slice(COOKIE.length+1);return /^hws_[A-Za-z0-9_-]{43}$/.test(value)?value:'';
}
export async function memberRoute(request,page,fetcher=fetch){
 const url=new URL(request.url),path=url.pathname;
 if(path==='/login'||path==='/account/')return new Response(null,{status:308,headers:{...STAFF_SECURITY,Location:'/account'}});
 if(path==='/account'){
  if(!['GET','HEAD'].includes(request.method))return staffError(405,'Use GET or HEAD.');
  return new Response(request.method==='HEAD'?null:page,{headers:{...STAFF_SECURITY,'Content-Type':'text/html; charset=utf-8'}});
 }
 if(path==='/api/community/staff/news'){
  const token=websiteCookie(request);if(!token){if(request.headers.has('authorization'))return staffRoute(request,'',fetcher);return staffError(401,'Please sign in to your account.');}
  const headers=new Headers(request.headers);headers.set('Authorization','Bearer '+token);return staffRoute(new Request(request,{headers}),'',fetcher);
 }
 const routes={'/api/site/login':['POST','/api/auth/website-login',false],'/api/site/recovery':['POST','/api/auth/forgot-password',false],'/api/site/account':['GET','/api/community/account',true],'/api/site/logout':['POST','/api/community/account/logout',true],'/api/site/verify-email':['POST','/api/community/account/verify-email',true]};
 if(!Object.hasOwn(routes,path))return null;
 const[method,target,privateRoute]=routes[path];if(request.method!==method)return staffError(405,'Method not allowed.');
 if(method==='POST'&&request.headers.get('origin')!==url.origin)return staffError(403,'Open your account on this website before continuing.');
 if(method==='POST'&&!/^application\/json(?:\s*;|$)/i.test(request.headers.get('content-type')||''))return staffError(415,'Use JSON.');
 const token=websiteCookie(request);if(privateRoute&&!token)return staffError(401,'Please sign in to your account.');
 let body;if(method==='POST'){try{body=await staffBody(request,8192);}catch{return staffError(413,'Request is too large.');}}
 const headers=new Headers({Accept:'application/json'});if(privateRoute)headers.set('Authorization','Bearer '+token);if(method==='POST'){headers.set('Origin',WEBSITE_UPSTREAM);headers.set('Content-Type','application/json');}
 try{
  const response=await fetcher(WEBSITE_UPSTREAM+target,{method,headers,body,redirect:'manual',signal:AbortSignal.timeout(15000)});
  if(response.status>=300&&response.status<400)return staffError(502,'The account service returned an unexpected redirect.');
  const resultHeaders=new Headers({...STAFF_SECURITY,'Content-Type':'application/json; charset=utf-8'});
  if(response.status===401)resultHeaders.set('Set-Cookie',clearCookie());
  if(path==='/api/site/login'&&response.ok){
   const data=await response.json();if(!/^hws_[A-Za-z0-9_-]{43}$/.test(data.accessToken||''))return staffError(502,'The account service returned an invalid session.');
   const age=Math.min(1800,Math.floor(Number(data.accessTokenExpiresAt)-Date.now()/1000));if(!(age>0))return staffError(502,'The account session has expired. Please try again.');
   resultHeaders.set('Set-Cookie',COOKIE+'='+data.accessToken+'; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age='+age);
   return new Response('{"ok":true}',{headers:resultHeaders});
  }
  if(path==='/api/site/logout'&&response.ok)resultHeaders.set('Set-Cookie',clearCookie());
  return new Response(response.body,{status:response.status,headers:resultHeaders});
 }catch{return staffError(502,'Your account is temporarily unavailable. Please try again.');}
}
