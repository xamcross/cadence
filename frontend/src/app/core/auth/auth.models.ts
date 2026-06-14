export type Role = 'ADMIN' | 'RECRUITER' | 'HIRING_MANAGER' | 'INTERVIEWER' | 'READ_ONLY';

export interface MemberSummary {
  memberId: string;
  workspaceId: string;
  role: Role;
  displayName: string;
  email: string;
}

export interface InvitationView {
  email: string;
  role: Role;
  needsPassword: boolean;
}
