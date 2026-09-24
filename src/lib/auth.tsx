import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { authError, login as apiLogin, logout as apiLogout, me, register as apiRegister, type AuthSession, type AuthUser } from "./api/auth";
import { setUnauthorizedHandler } from "./api/client";
type AuthState = { session: AuthSession | null; user: AuthUser | null; loading: boolean; signIn: (email: string, password: string) => Promise<{ error: string | null }>; signUp: (email: string, password: string) => Promise<{ error: string | null }>; signOut: () => Promise<void> };
const AuthContext = createContext<AuthState | null>(null);
export function AuthProvider({ children }: { children: ReactNode }) {
 const [session,setSession]=useState<AuthSession|null>(null); const [loading,setLoading]=useState(true);
 useEffect(()=>{ let active=true; setUnauthorizedHandler(()=>{if(active)setSession(null)}); me().then(user=>{if(active)setSession({accessToken:"",user})}).catch(()=>{if(active)setSession(null)}).finally(()=>{if(active)setLoading(false)}); return()=>{active=false;setUnauthorizedHandler(()=>{})} },[]);
 async function result(action:()=>Promise<AuthSession>){try{setSession(await action());return{error:null}}catch(e){return{error:authError(e)}}}
 const signIn=(email:string,password:string)=>result(()=>apiLogin(email,password)); const signUp=(email:string,password:string)=>result(()=>apiRegister(email,password));
 async function signOut(){await apiLogout();setSession(null)} return <AuthContext.Provider value={{session,user:session?.user??null,loading,signIn,signUp,signOut}}>{children}</AuthContext.Provider>;
}
export function useAuth():AuthState{const ctx=useContext(AuthContext);if(!ctx)throw new Error("useAuth must be used inside AuthProvider");return ctx}

