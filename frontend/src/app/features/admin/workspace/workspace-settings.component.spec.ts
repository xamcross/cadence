import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { WorkspaceSettingsComponent } from './workspace-settings.component';
import { WorkspaceConfig, WorkspaceService } from './workspace.service';

/**
 * F03 US6 (SC-012 sibling): the settings component renders for an Admin and loads the config. The
 * per-role guard redirect is covered by role.guard.spec (the same roleGuard('ADMIN') guards both the
 * settings and wizard routes); the server is the security boundary.
 */
describe('WorkspaceSettingsComponent', () => {
  const config: WorkspaceConfig = {
    configured: true, name: 'Acme', timeZone: 'Europe/London',
    workingHours: { start: '09:00', end: '17:00' },
    slaSilenceWindowDays: 5, retentionPeriodDays: 365, retentionAcknowledgedAt: '2026-06-14T00:00:00Z',
    brandColor: '#1F2937', hasLogo: false, emailSendingDomain: null, credentialSet: false,
    templateLocks: { interview_invite: true }
  };

  function setup() {
    const stub: Partial<WorkspaceService> = { getConfig: () => of(config) };
    TestBed.configureTestingModule({
      imports: [WorkspaceSettingsComponent],
      providers: [{ provide: WorkspaceService, useValue: stub }]
    });
    const fixture = TestBed.createComponent(WorkspaceSettingsComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('loads and renders the config for an Admin', () => {
    const fixture = setup();
    expect(fixture.componentInstance.config()?.name).toBe('Acme');
    expect(fixture.nativeElement.textContent).toContain('Workspace settings');
  });

  it('lists existing template lock keys', () => {
    const fixture = setup();
    expect(fixture.componentInstance.templateKeys()).toContain('interview_invite');
  });

  it('reflects credential-not-set state', () => {
    const fixture = setup();
    expect(fixture.nativeElement.textContent).toContain('not set');
  });
});
