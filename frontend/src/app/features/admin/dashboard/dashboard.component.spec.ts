import { TestBed, ComponentFixture } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { DashboardComponent } from './dashboard.component';
import { DashboardService, DashboardSnapshot, DashboardWindow } from './dashboard.service';
import { AuthService } from '../../../core/auth/auth.service';

function makeSnap(overrides: Partial<DashboardSnapshot> = {}): DashboardSnapshot {
  return {
    window: 'LAST_30_DAYS',
    generatedAt: '2026-06-18T10:00:00Z',
    timeToSchedule: { hasData: true, medianHours: 18.5, sampleCount: 12 },
    noShow: { applicable: true, rate: 0.2, noShowCount: 2, qualifyingCount: 10 },
    silenceList: [{ candidateId: 'c1', candidateName: 'Jordan Lee', severity: 'RED', daysSilent: 9 }],
    ...overrides
  };
}

describe('DashboardComponent', () => {
  let dash: {
    selectedWindow: ReturnType<typeof signal<DashboardWindow>>;
    snapshot: jasmine.Spy;
    download: jasmine.Spy;
    exportUrl: (w: DashboardWindow) => string;
  };
  let auth: { me: jasmine.Spy };

  function setup(role: string = 'ADMIN'): ComponentFixture<DashboardComponent> {
    dash = {
      selectedWindow: signal<DashboardWindow>('LAST_30_DAYS'),
      snapshot: jasmine.createSpy('snapshot').and.returnValue(of(makeSnap())),
      download: jasmine.createSpy('download'),
      exportUrl: (w) => `/api/internal/dashboard/export?window=${w}`
    };
    auth = { me: jasmine.createSpy('me').and.returnValue(of({ role })) };

    TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        { provide: DashboardService, useValue: dash },
        { provide: AuthService, useValue: auth }
      ]
    });
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('loads the default window snapshot and renders the median', () => {
    const fixture = setup();
    expect(dash.snapshot).toHaveBeenCalledWith('LAST_30_DAYS');
    expect(fixture.nativeElement.textContent).toContain('18.5');
  });

  it('recomputes when the window changes', () => {
    const fixture = setup();
    const buttons: HTMLButtonElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('.window-btn'));
    const last7 = buttons.find((b) => b.textContent?.includes('7'))!;
    last7.click();
    fixture.detectChanges();
    expect(dash.snapshot).toHaveBeenCalledWith('LAST_7_DAYS');
  });

  it('renders empty / not-applicable states', () => {
    TestBed.resetTestingModule();
    dash = {
      selectedWindow: signal<DashboardWindow>('LAST_30_DAYS'),
      snapshot: jasmine.createSpy('snapshot').and.returnValue(of(makeSnap({
        timeToSchedule: { hasData: false, medianHours: null, sampleCount: 0 },
        noShow: { applicable: false, rate: null, noShowCount: 0, qualifyingCount: 0 },
        silenceList: []
      }))),
      download: jasmine.createSpy('download'),
      exportUrl: (w) => `/x?window=${w}`
    };
    auth = { me: jasmine.createSpy('me').and.returnValue(of({ role: 'ADMIN' })) };
    TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        { provide: DashboardService, useValue: dash },
        { provide: AuthService, useValue: auth }
      ]
    });
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('No data for this window');
    expect(text).toContain('Not applicable');
  });

  it('hides the export control for a Read-only user', () => {
    const fixture = setup('READ_ONLY');
    expect(fixture.nativeElement.querySelector('.export-btn')).toBeNull();
  });

  it('export triggers a download for the SELECTED window (stale-window guard)', () => {
    const fixture = setup('RECRUITER');
    const buttons: HTMLButtonElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('.window-btn'));
    buttons.find((b) => b.textContent?.includes('90'))!.click();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.export-btn').click();
    expect(dash.download).toHaveBeenCalledWith('LAST_90_DAYS');
  });
});
