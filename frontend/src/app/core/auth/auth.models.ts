export type Role = 'ADMIN' | 'RECRUITER' | 'HIRING_MANAGER' | 'INTERVIEWER' | 'READ_ONLY';

export interface MemberSummary {
  memberId: string;
  workspaceId: string;
  role: Role;
  displayName: string;
  email: string;
  // F03: whether the workspace has completed first-run setup — drives shell routing (D3).
  workspaceConfigured: boolean;
}

export interface InvitationView {
  email: string;
  role: Role;
  needsPassword: boolean;
}
