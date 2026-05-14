interface StepHeaderProps {
  step: number
  title: string
  subtitle: string
}

export function StepHeader({ step, title, subtitle }: StepHeaderProps) {
  return (
    <div className="step-header">
      <span className="step-badge">Step {step}</span>
      <div>
        <h1 className="step-title">{title}</h1>
        <p className="step-sub">{subtitle}</p>
      </div>
    </div>
  )
}
