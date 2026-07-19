import { TestBed } from '@angular/core/testing';
import { NEVER, of } from 'rxjs';
import { WorkspaceSettingsComponent } from './workspace-settings.component';
import { WorkspaceConfig, WorkspaceService } from './workspace.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

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

  let attachedEls: HTMLElement[] = [];

  function setup(overrides: Partial<WorkspaceService> = {}) {
    const stub: Partial<WorkspaceService> = { getConfig: () => of(config), ...overrides };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [WorkspaceSettingsComponent],
      providers: [{ provide: WorkspaceService, useValue: stub }]
    });
    const fixture = TestBed.createComponent(WorkspaceSettingsComponent);
    const el = fixture.nativeElement as HTMLElement;
    attachedEls.push(el);
    attachToBody(el);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    attachedEls.forEach(detachFromBody);
    attachedEls = [];
  });

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

  it('renders the shared page-header masthead', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setup();
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });

  it('shows a form skeleton while the config has not yet loaded', () => {
    const fixture = setup({ getConfig: () => NEVER });
    expect(fixture.componentInstance.config()).toBeNull();
    expect(fixture.nativeElement.querySelector('app-skeleton')).not.toBeNull();
  });
});
